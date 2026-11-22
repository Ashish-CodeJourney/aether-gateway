package com.aether.gateway.providers.openai;

import com.aether.gateway.core.domain.EmbeddingOutcome;
import com.aether.gateway.core.domain.EmbeddingRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The embeddings counterpart of {@link OpenAiCompatibleAdapterLiveTest}:
 * the same self-hosted LiteLLM proxy, proving the passthrough embeddings
 * path against a real OpenAI-compatible server rather than recorded
 * fixtures. Needs an embedding model served by that proxy, named in
 * {@code LITELLM_EMBEDDING_MODEL}; skipped unless both variables are set:
 * <pre>
 * set -a; . ./.env; set +a
 * LITELLM_EMBEDDING_MODEL=nomic-embed-text ./gradlew :gateway-providers:test --tests '*EmbeddingLiveTest'
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "LITELLM_API_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "LITELLM_EMBEDDING_MODEL", matches = ".+")
class OpenAiCompatibleEmbeddingLiveTest {

    private static final String BASE_URL = "https://ai.nelkinda.com/v1";

    private OpenAiCompatibleAdapter adapter() {
        return new OpenAiCompatibleAdapter(
                "litellm-nelkinda", BASE_URL, System.getenv("LITELLM_API_KEY"), RestClient.builder(), WebClient.builder());
    }

    @Test
    void embedsABatchAgainstARealSelfHostedOpenAiCompatibleProxy() {
        var outcome = adapter().embed(new EmbeddingRequest(
                System.getenv("LITELLM_EMBEDDING_MODEL"), List.of("the cat sat", "on the mat"), null));

        assertThat(outcome)
                .as("a real embedding, not a Failure - check the embedding model is pulled on the backing host")
                .isInstanceOf(EmbeddingOutcome.Success.class);
        var response = ((EmbeddingOutcome.Success) outcome).response();
        assertThat(response.data()).hasSize(2);
        assertThat(response.data().get(0).vector()).isNotEmpty().hasSameSizeAs(response.data().get(1).vector());
        assertThat(response.data().get(0).vector()).isNotEqualTo(response.data().get(1).vector());
    }

    @Test
    void reportsAnUnknownModelAsAFailureRatherThanThrowing() {
        var outcome = adapter().embed(new EmbeddingRequest("mac/definitely-not-a-real-model", List.of("hi"), null));

        assertThat(outcome).isInstanceOf(EmbeddingOutcome.Failure.class);
    }
}
