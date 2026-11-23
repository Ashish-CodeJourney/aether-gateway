package com.aether.gateway.providers.ollama;

import com.aether.gateway.core.domain.ChatCompletionRequest;
import com.aether.gateway.core.domain.ChatMessage;
import com.aether.gateway.core.domain.ProviderResponse;
import com.aether.gateway.core.domain.ToolCall;
import com.aether.gateway.core.domain.ToolCallDelta;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.adapter.JdkFlowAdapter;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tool calling through the native {@code /api/chat} adapter against a real
 * Ollama, proving the contract fixtures match what Ollama actually sends.
 * Skipped unless {@code AETHER_LIVE_OLLAMA_BASE_URL} and
 * {@code AETHER_LIVE_OLLAMA_MODEL} are set; the model must support tools
 * (e.g. llama3.1, qwen3). Run it with:
 * <pre>
 * export AETHER_LIVE_OLLAMA_BASE_URL=http://localhost:11434
 * export AETHER_LIVE_OLLAMA_MODEL=llama3.1
 * ./gradlew :gateway-providers:test --tests '*OllamaAdapterLiveTest'
 * </pre>
 *
 * <p>Ollama cannot force a tool call, so the prompt asks for one; a model
 * that answers in prose instead fails these tests, which says more about
 * the model than the adapter.
 */
@EnabledIfEnvironmentVariable(named = "AETHER_LIVE_OLLAMA_BASE_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "AETHER_LIVE_OLLAMA_MODEL", matches = ".+")
class OllamaAdapterLiveTest {

    private static final String BASE_URL = System.getenv("AETHER_LIVE_OLLAMA_BASE_URL");
    private static final String MODEL = System.getenv("AETHER_LIVE_OLLAMA_MODEL");

    private static final String WEATHER_TOOL = """
            {"type":"function","function":{"name":"get_weather","description":"Current weather for a city","parameters":{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}}}""";

    private static final ChatMessage QUESTION = new ChatMessage("user", "What is the weather in Paris? Use the get_weather tool.");

    private OllamaAdapter adapter() {
        return new OllamaAdapter("live-ollama", BASE_URL, null, RestClient.builder(), WebClient.builder());
    }

    private ChatCompletionRequest request(boolean stream, ChatMessage... messages) {
        return new ChatCompletionRequest(MODEL, List.of(messages), stream, null, List.of(WEATHER_TOOL), null);
    }

    @Test
    void relaysARealToolCallAndCompletesTheRoundTripWithItsResult() {
        var first = adapter().invoke(request(false, QUESTION));

        assertThat(first).isInstanceOf(ProviderResponse.Completion.class);
        var choice = ((ProviderResponse.Completion) first).response().choices().get(0);
        assertThat(choice.finishReason()).isEqualTo("tool_calls");
        ToolCall call = choice.message().toolCalls().get(0);
        assertThat(call.id()).isNotBlank();
        assertThat(call.functionName()).isEqualTo("get_weather");
        assertThat(call.argumentsJson()).containsIgnoringCase("paris");

        var second = adapter().invoke(request(false,
                QUESTION,
                new ChatMessage("assistant", "", List.of(call), null),
                new ChatMessage("tool", "{\"temperature_c\": 18, \"sky\": \"clear\"}", List.of(), call.id())));

        assertThat(second).isInstanceOf(ProviderResponse.Completion.class);
        var answer = ((ProviderResponse.Completion) second).response().choices().get(0);
        assertThat(answer.message().toolCalls()).isEmpty();
        assertThat(answer.message().content()).contains("18");
    }

    @Test
    void streamsARealToolCallAsADelta() {
        var chunks = JdkFlowAdapter.flowPublisherToFlux(adapter().invokeStreaming(request(true, QUESTION)))
                .collectList()
                .block(Duration.ofMinutes(5));

        assertThat(chunks).isNotEmpty();
        List<ToolCallDelta> deltas = chunks.stream().flatMap(chunk -> chunk.toolCallDeltas().stream()).toList();
        assertThat(deltas).isNotEmpty();
        assertThat(deltas.get(0).functionName()).isEqualTo("get_weather");
        assertThat(deltas.get(0).argumentsFragment()).containsIgnoringCase("paris");
        assertThat(chunks.getLast().last()).isTrue();
        assertThat(chunks.getLast().finishReason()).isEqualTo("tool_calls");
    }
}
