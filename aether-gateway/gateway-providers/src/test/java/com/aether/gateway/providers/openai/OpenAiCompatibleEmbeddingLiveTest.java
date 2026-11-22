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
 * The embeddings counterpart of {@link OpenAiCompatibleAdapterLiveTest},
 * proving the passthrough embeddings path against a real
 * OpenAI-compatible server rather than recorded fixtures. The endpoint is
 * supplied entirely through the environment, so no real deployment's
 * hostname or credential lives in the repository. Skipped unless
 * {@code AETHER_LIVE_OPENAI_BASE_URL}, {@code AETHER_LIVE_OPENAI_API_KEY}
 * and {@code AETHER_LIVE_OPENAI_EMBEDDING_MODEL} are all set:
 * <pre>
 * export AETHER_LIVE_OPENAI_BASE_URL=https://your-proxy.example.com/v1
 * export AETHER_LIVE_OPENAI_API_KEY=...
 * export AETHER_LIVE_OPENAI_EMBEDDING_MODEL=nomic-embed-text
 * ./gradlew :gateway-providers:test --tests '*EmbeddingLiveTest'
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "AETHER_LIVE_OPENAI_BASE_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "AETHER_LIVE_OPENAI_API_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "AETHER_LIVE_OPENAI_EMBEDDING_MODEL", matches = ".+")
class OpenAiCompatibleEmbeddingLiveTest {

    private static final String PROVIDER_ID = "live-openai-compatible";
    private static final String BASE_URL = System.getenv("AETHER_LIVE_OPENAI_BASE_URL");
    private static final String API_KEY = System.getenv("AETHER_LIVE_OPENAI_API_KEY");
    private static final String MODEL = System.getenv("AETHER_LIVE_OPENAI_EMBEDDING_MODEL");

    private OpenAiCompatibleAdapter adapter() {
        return new OpenAiCompatibleAdapter(PROVIDER_ID, BASE_URL, API_KEY, RestClient.builder(), WebClient.builder());
    }

    @Test
    void embedsABatchAgainstARealSelfHostedOpenAiCompatibleProxy() {
        var outcome = adapter().embed(new EmbeddingRequest(
                MODEL, List.of("the cat sat", "on the mat"), null));

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
        var outcome = adapter().embed(new EmbeddingRequest("definitely-not-a-real-model", List.of("hi"), null));

        assertThat(outcome).isInstanceOf(EmbeddingOutcome.Failure.class);
    }
}
