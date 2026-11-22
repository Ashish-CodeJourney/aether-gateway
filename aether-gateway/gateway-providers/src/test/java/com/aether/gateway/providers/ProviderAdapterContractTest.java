package com.aether.gateway.providers;

import com.aether.gateway.core.domain.ChatCompletionRequest;
import com.aether.gateway.core.domain.ChatMessage;
import com.aether.gateway.core.domain.ProviderResponse;
import com.aether.gateway.core.domain.ProviderStreamException;
import com.aether.gateway.core.domain.Usage;
import com.aether.gateway.core.port.ProviderAdapter;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.adapter.JdkFlowAdapter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 12 (M9): the shared {@link ProviderAdapter} contract this
 * M9 milestone's own definition (docs/design/requirements.md,
 * release.md, "Design notes carried in from Phase 02") calls for -
 * "OllamaAdapter, GroqAdapter, GeminiAdapter, and the generic
 * OpenAI-compatible adapter must each pass the exact same
 * ProviderAdapter contract test suite written in Phase 05, with zero
 * changes to gateway-core or gateway-router." That suite did not
 * actually exist anywhere in the tree (verified by search before
 * writing this); this is it, written now as the prerequisite M9
 * depends on. Runs against a real local HTTP server (MockWebServer),
 * not a hand-rolled fake, so each concrete adapter's real HTTP client
 * wiring (headers, status handling, streaming decode) is genuinely
 * exercised - only the upstream response bytes are canned, per PRD
 * section 15's "Contract" row ("recorded fixtures") testing strategy.
 *
 * <p>Each concrete provider adapter gets its own subclass supplying
 * that provider's real wire format for the fixtures below; the test
 * methods themselves assert only adapter-agnostic {@link ProviderAdapter}
 * behaviour, so a subclass proves its adapter satisfies the contract
 * without the contract itself knowing anything about wire formats.
 */
public abstract class ProviderAdapterContractTest {

    protected MockWebServer server;
    protected ProviderAdapter adapter;

    @BeforeEach
    void startServerAndAdapter() throws IOException {
        server = new MockWebServer();
        server.start();
        adapter = createAdapter(server.url("/").toString());
    }

    @AfterEach
    void stopServer() throws IOException {
        server.shutdown();
    }

    /** Build the adapter under test, pointed at the given local server base URL. */
    protected abstract ProviderAdapter createAdapter(String baseUrl);

    /** A raw HTTP response representing a successful non-streaming completion, in this provider's real wire format. */
    protected abstract MockResponse successResponse();

    /** The assistant message content {@link #successResponse()} encodes, for the test to assert against. */
    protected abstract String expectedSuccessContent();

    /** A raw HTTP response representing this provider's real rate-limit error shape. */
    protected abstract MockResponse rateLimitResponse();

    /** A raw HTTP response representing this provider's real server-error shape. */
    protected abstract MockResponse serverErrorResponse();

    /** A raw HTTP response representing a full streamed reply, in this provider's real streaming wire format. */
    protected abstract MockResponse streamingResponse();

    /** The concatenation of every streamed chunk's delta content {@link #streamingResponse()} encodes, in order. */
    protected abstract String expectedStreamedContent();

    /** The token usage the provider reports for the whole of {@link #streamingResponse()}. */
    protected abstract Usage expectedStreamedUsage();

    private ChatCompletionRequest sampleRequest() {
        return new ChatCompletionRequest(
                "test-model", List.of(new ChatMessage("user", "hello")), false);
    }

    @Test
    void providerNameIsExposedAsConfigured() {
        assertThat(adapter.providerName()).isNotBlank();
    }

    @Test
    void invokeReturnsACompletionOnSuccess() {
        server.enqueue(successResponse());

        ProviderResponse result = adapter.invoke(sampleRequest());

        assertThat(result).isInstanceOf(ProviderResponse.Completion.class);
        var completion = (ProviderResponse.Completion) result;
        assertThat(completion.response().choices()).isNotEmpty();
        assertThat(completion.response().choices().get(0).message().content())
                .isEqualTo(expectedSuccessContent());
    }

    @Test
    void invokeSendsTheRequestToTheAdapterUnderTest() throws InterruptedException {
        server.enqueue(successResponse());

        adapter.invoke(sampleRequest());

        RecordedRequest recorded = server.takeRequest(5, TimeUnit.SECONDS);
        assertThat(recorded).isNotNull();
        assertThat(recorded.getMethod()).isEqualTo("POST");
    }

    @Test
    void invokeReturnsARetryableErrorOnRateLimit() {
        server.enqueue(rateLimitResponse());

        ProviderResponse result = adapter.invoke(sampleRequest());

        assertThat(result).isInstanceOf(ProviderResponse.ProviderError.class);
        var error = (ProviderResponse.ProviderError) result;
        assertThat(error.retryable()).isTrue();
    }

    @Test
    void invokeReturnsAnErrorWithTheRealServerErrorStatusCode() {
        server.enqueue(serverErrorResponse());

        ProviderResponse result = adapter.invoke(sampleRequest());

        assertThat(result).isInstanceOf(ProviderResponse.ProviderError.class);
        var error = (ProviderResponse.ProviderError) result;
        assertThat(error.httpStatus()).isGreaterThanOrEqualTo(500);
    }

    @Test
    void invokeReturnsARetryableErrorWhenTheServerIsUnreachable() {
        ProviderAdapter deadAdapter = createAdapter("http://127.0.0.1:1"); // nothing listens here

        ProviderResponse result = deadAdapter.invoke(sampleRequest());

        assertThat(result).isInstanceOf(ProviderResponse.ProviderError.class);
        var error = (ProviderResponse.ProviderError) result;
        assertThat(error.retryable()).isTrue();
    }

    @Test
    void invokeStreamingAssemblesTheFullContentAndEndsWithALastChunk() {
        server.enqueue(streamingResponse());

        var publisher = adapter.invokeStreaming(sampleRequest());
        List<ProviderResponse.StreamChunk> chunks = JdkFlowAdapter.flowPublisherToFlux(publisher)
                .collectList()
                .block(Duration.ofSeconds(10));

        assertThat(chunks).isNotNull().isNotEmpty();
        String assembled = chunks.stream().map(ProviderResponse.StreamChunk::deltaContent).collect(joining());
        assertThat(assembled).isEqualTo(expectedStreamedContent());
        assertThat(chunks.get(chunks.size() - 1).last()).isTrue();
    }

    @Test
    void invokeStreamingReportsTheProvidersOwnUsageOnTheLastChunkOnly() {
        server.enqueue(streamingResponse());

        List<ProviderResponse.StreamChunk> chunks = JdkFlowAdapter.flowPublisherToFlux(adapter.invokeStreaming(sampleRequest()))
                .collectList()
                .block(Duration.ofSeconds(10));

        assertThat(chunks).isNotNull().isNotEmpty();
        var last = chunks.get(chunks.size() - 1);
        assertThat(last.last()).isTrue();
        assertThat(last.usage()).isEqualTo(expectedStreamedUsage());
        assertThat(chunks.subList(0, chunks.size() - 1)).allSatisfy(chunk -> {
            assertThat(chunk.last()).isFalse();
            assertThat(chunk.usage()).isNull();
        });
    }

    /** Helper for concrete subclasses building canned JSON response bodies. */
    private Throwable streamingFailure(ProviderAdapter target) {
        var request = new ChatCompletionRequest("test-model", List.of(new ChatMessage("user", "hello")), true);
        return JdkFlowAdapter.flowPublisherToFlux(target.invokeStreaming(request))
                .then()
                .map(ignored -> (Throwable) new AssertionError("stream completed without an error"))
                .onErrorResume(error -> reactor.core.publisher.Mono.just(error))
                .block(Duration.ofSeconds(10));
    }

    @Test
    void invokeStreamingReportsARejectedRequestAsATerminalProviderStreamExceptionWithTheRealStatus() {
        server.enqueue(jsonResponse(400, """
                {"error": {"message": "bad request", "type": "invalid_request_error"}}
                """));

        Throwable failure = streamingFailure(adapter);

        assertThat(failure).isInstanceOf(ProviderStreamException.class);
        var error = (ProviderStreamException) failure;
        assertThat(error.httpStatus()).isEqualTo(400);
        assertThat(error.errorCode()).isEqualTo("provider_error");
        assertThat(error.retryable()).isFalse();
    }

    @Test
    void invokeStreamingReportsAServerErrorAsARetryableProviderStreamException() {
        server.enqueue(serverErrorResponse());

        Throwable failure = streamingFailure(adapter);

        assertThat(failure).isInstanceOf(ProviderStreamException.class);
        var error = (ProviderStreamException) failure;
        assertThat(error.httpStatus()).isGreaterThanOrEqualTo(500);
        assertThat(error.retryable()).isTrue();
    }

    @Test
    void invokeStreamingReportsARateLimitAsARetryableProviderStreamException() {
        server.enqueue(rateLimitResponse());

        Throwable failure = streamingFailure(adapter);

        assertThat(failure).isInstanceOf(ProviderStreamException.class);
        var error = (ProviderStreamException) failure;
        assertThat(error.httpStatus()).isEqualTo(429);
        assertThat(error.retryable()).isTrue();
    }

    @Test
    void invokeStreamingReportsAnUnreachableServerAsARetryableBadGateway() {
        Throwable failure = streamingFailure(createAdapter("http://127.0.0.1:1"));

        assertThat(failure).isInstanceOf(ProviderStreamException.class);
        var error = (ProviderStreamException) failure;
        assertThat(error.httpStatus()).isEqualTo(502);
        assertThat(error.errorCode()).isEqualTo("provider_unreachable");
        assertThat(error.retryable()).isTrue();
    }

    protected static MockResponse jsonResponse(int status, String body) {
        return new MockResponse()
                .setResponseCode(status)
                .setBody(body)
                .setHeader("Content-Type", "application/json");
    }
}
