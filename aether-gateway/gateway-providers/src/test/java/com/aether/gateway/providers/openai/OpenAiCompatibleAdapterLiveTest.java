package com.aether.gateway.providers.openai;

import com.aether.gateway.core.domain.ChatCompletionRequest;
import com.aether.gateway.core.domain.ChatMessage;
import com.aether.gateway.core.domain.ProviderResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.adapter.JdkFlowAdapter;

import java.time.Duration;
import java.util.List;

import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The only test in this repository that talks to a provider nobody here
 * controls. Every other provider test runs against MockWebServer with
 * recorded fixtures, which proves the adapter parses a wire format
 * correctly but cannot prove that format is what a real deployment
 * actually sends.
 *
 * <p>Target is a self-hosted LiteLLM proxy fronting Ollama - the generic
 * OpenAI-compatible path, with no adapter written for it specifically.
 * That is the claim under test: "anything speaking the OpenAI wire
 * format works unmodified" should hold against a real one.
 *
 * <p>Skipped unless {@code LITELLM_API_KEY} is set, so CI and a clean
 * checkout stay green and offline. Run it with:
 * <pre>
 * set -a; . ./.env; set +a
 * ./gradlew :gateway-providers:test --tests '*LiveTest'
 * </pre>
 *
 * <p>The models behind this proxy are reasoning models: they spend
 * output tokens thinking before emitting any content, and LiteLLM
 * returns that thinking in a non-standard {@code reasoning_content}
 * field this gateway neither reads nor needs. A tight {@code max_tokens}
 * therefore yields an empty {@code content} with a perfectly normal
 * {@code finish_reason} - worth knowing before concluding an adapter is
 * broken.
 */
@EnabledIfEnvironmentVariable(named = "LITELLM_API_KEY", matches = ".+")
class OpenAiCompatibleAdapterLiveTest {

    private static final String BASE_URL = "https://ai.nelkinda.com/v1";
    private static final String MODEL = "mac/gemma4:12b-mlx";

    private OpenAiCompatibleAdapter adapter() {
        return new OpenAiCompatibleAdapter(
                "litellm-nelkinda",
                BASE_URL,
                System.getenv("LITELLM_API_KEY"),
                RestClient.builder(),
                WebClient.builder());
    }

    private ChatCompletionRequest request(boolean stream) {
        return new ChatCompletionRequest(
                MODEL,
                List.of(new ChatMessage("user", "Reply with exactly one word: pong")),
                stream);
    }

    @Test
    void completesAgainstARealSelfHostedOpenAiCompatibleProxy() {
        ProviderResponse result = adapter().invoke(request(false));

        assertThat(result)
                .as("a real completion, not a ProviderError - check the model is pulled on the backing host")
                .isInstanceOf(ProviderResponse.Completion.class);

        var response = ((ProviderResponse.Completion) result).response();
        assertThat(response.choices()).isNotEmpty();
        assertThat(response.choices().get(0).message().content()).containsIgnoringCase("pong");
        assertThat(response.usage().promptTokens()).isPositive();
        assertThat(response.usage().completionTokens()).isPositive();
    }

    @Test
    void streamsAgainstARealSelfHostedOpenAiCompatibleProxy() {
        var chunks = JdkFlowAdapter.flowPublisherToFlux(adapter().invokeStreaming(request(true)))
                .collectList()
                .block(Duration.ofMinutes(3));

        assertThat(chunks).isNotNull().isNotEmpty();
        assertThat(chunks.stream().map(ProviderResponse.StreamChunk::deltaContent).collect(joining()))
                .containsIgnoringCase("pong");
        assertThat(chunks.get(chunks.size() - 1).last()).isTrue();
    }

    @Test
    void reportsAnUnknownModelAsAProviderErrorRatherThanThrowing() {
        var result = new OpenAiCompatibleAdapter(
                "litellm-nelkinda", BASE_URL, System.getenv("LITELLM_API_KEY"),
                RestClient.builder(), WebClient.builder())
                .invoke(new ChatCompletionRequest(
                        "mac/definitely-not-a-real-model",
                        List.of(new ChatMessage("user", "hi")),
                        false));

        assertThat(result).isInstanceOf(ProviderResponse.ProviderError.class);
    }
}
