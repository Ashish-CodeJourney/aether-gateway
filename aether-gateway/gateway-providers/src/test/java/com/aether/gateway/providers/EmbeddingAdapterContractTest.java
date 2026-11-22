package com.aether.gateway.providers;

import com.aether.gateway.core.domain.Embedding;
import com.aether.gateway.core.domain.EmbeddingOutcome;
import com.aether.gateway.core.domain.EmbeddingRequest;
import com.aether.gateway.core.port.ProviderAdapter;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The embeddings counterpart of {@link ProviderAdapterContractTest}: the
 * same adapter-agnostic assertions run against every adapter that
 * supports {@code embed}, each subclass supplying its provider's real
 * wire format for two inputs, "hello" then "world", embedding to
 * {@code [0.1, 0.2, 0.3]} and {@code [0.4, 0.5, 0.6]} respectively.
 * Kept separate from the chat contract so the mock adapter, which has
 * no chat contract subclass, can run it too.
 */
public abstract class EmbeddingAdapterContractTest {

    protected static final List<Float> HELLO_VECTOR = List.of(0.1f, 0.2f, 0.3f);
    protected static final List<Float> WORLD_VECTOR = List.of(0.4f, 0.5f, 0.6f);

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

    protected abstract ProviderAdapter createAdapter(String baseUrl);

    /** This provider's real success body embedding "hello" and "world" to {@link #HELLO_VECTOR} and {@link #WORLD_VECTOR}. */
    protected abstract MockResponse embeddingSuccessResponse();

    /** What the adapter should report as prompt tokens for {@link #embeddingSuccessResponse()}. */
    protected abstract int expectedPromptTokens();

    protected abstract MockResponse rateLimitResponse();

    protected abstract MockResponse serverErrorResponse();

    protected static EmbeddingRequest helloWorldRequest(Integer dimensions) {
        return new EmbeddingRequest("embed-model", List.of("hello", "world"), dimensions);
    }

    protected static MockResponse jsonResponse(int status, String body) {
        return new MockResponse()
                .setResponseCode(status)
                .setBody(body)
                .setHeader("Content-Type", "application/json");
    }

    protected RecordedRequest takeRequest() throws InterruptedException {
        RecordedRequest recorded = server.takeRequest(5, TimeUnit.SECONDS);
        assertThat(recorded).as("the adapter made an HTTP request").isNotNull();
        return recorded;
    }

    @Test
    void returnsOneVectorPerInputInInputOrder() {
        server.enqueue(embeddingSuccessResponse());

        EmbeddingOutcome outcome = adapter.embed(helloWorldRequest(null));

        assertThat(outcome).isInstanceOf(EmbeddingOutcome.Success.class);
        var data = ((EmbeddingOutcome.Success) outcome).response().data();
        assertThat(data).extracting(Embedding::index).containsExactly(0, 1);
        assertThat(data.get(0).vector()).containsExactlyElementsOf(HELLO_VECTOR);
        assertThat(data.get(1).vector()).containsExactlyElementsOf(WORLD_VECTOR);
    }

    @Test
    void reportsPromptTokenUsage() {
        server.enqueue(embeddingSuccessResponse());

        var outcome = (EmbeddingOutcome.Success) adapter.embed(helloWorldRequest(null));

        assertThat(outcome.response().promptTokens()).isEqualTo(expectedPromptTokens());
    }

    @Test
    void sendsBothInputsInASingleRequest() throws InterruptedException {
        server.enqueue(embeddingSuccessResponse());

        adapter.embed(helloWorldRequest(null));

        String body = takeRequest().getBody().readUtf8();
        assertThat(body).contains("hello").contains("world");
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void reportsARateLimitAsARetryableFailure() {
        server.enqueue(rateLimitResponse());

        var outcome = adapter.embed(helloWorldRequest(null));

        assertThat(outcome).isInstanceOf(EmbeddingOutcome.Failure.class);
        assertThat(((EmbeddingOutcome.Failure) outcome).error().retryable()).isTrue();
    }

    @Test
    void reportsAServerErrorWithItsRealStatusCode() {
        server.enqueue(serverErrorResponse());

        var outcome = adapter.embed(helloWorldRequest(null));

        assertThat(outcome).isInstanceOf(EmbeddingOutcome.Failure.class);
        assertThat(((EmbeddingOutcome.Failure) outcome).error().httpStatus()).isGreaterThanOrEqualTo(500);
    }

    @Test
    void reportsAnUnreachableProviderAsARetryableFailure() {
        var outcome = createAdapter("http://127.0.0.1:1").embed(helloWorldRequest(null));

        assertThat(outcome).isInstanceOf(EmbeddingOutcome.Failure.class);
        assertThat(((EmbeddingOutcome.Failure) outcome).error().retryable()).isTrue();
    }
}
