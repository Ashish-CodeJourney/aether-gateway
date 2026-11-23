package com.aether.gateway.providers.gemini;

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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tool calling against the real Gemini API, proving the contract fixtures,
 * the schema conversion and the thought-signature placeholder hold up
 * against what Google actually accepts and sends. Skipped unless
 * {@code AETHER_LIVE_GEMINI_API_KEY} and {@code AETHER_LIVE_GEMINI_MODEL}
 * are set; {@code AETHER_LIVE_GEMINI_BASE_URL} optionally overrides the
 * public endpoint. Run it with:
 * <pre>
 * export AETHER_LIVE_GEMINI_API_KEY=...
 * export AETHER_LIVE_GEMINI_MODEL=gemini-2.5-flash
 * ./gradlew :gateway-providers:test --tests '*GeminiAdapterLiveTest'
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "AETHER_LIVE_GEMINI_API_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "AETHER_LIVE_GEMINI_MODEL", matches = ".+")
class GeminiAdapterLiveTest {

    private static final String BASE_URL = Optional.ofNullable(System.getenv("AETHER_LIVE_GEMINI_BASE_URL"))
            .filter(url -> !url.isBlank())
            .orElse("https://generativelanguage.googleapis.com");
    private static final String API_KEY = System.getenv("AETHER_LIVE_GEMINI_API_KEY");
    private static final String MODEL = System.getenv("AETHER_LIVE_GEMINI_MODEL");

    /** additionalProperties and $schema are deliberately present: Gemini rejects them unless the adapter strips them. */
    private static final String WEATHER_TOOL = """
            {"type":"function","function":{"name":"get_weather","description":"Current weather for a city","parameters":{
              "$schema":"http://json-schema.org/draft-07/schema#","type":"object","additionalProperties":false,
              "properties":{"city":{"type":"string"},"unit":{"type":["string","null"],"enum":["celsius","fahrenheit"]}},
              "required":["city"]}}}""";

    private static final ChatMessage QUESTION = new ChatMessage("user", "What is the weather in Paris?");

    private GeminiAdapter adapter() {
        return new GeminiAdapter("live-gemini", BASE_URL, API_KEY, RestClient.builder(), WebClient.builder());
    }

    private ChatCompletionRequest request(boolean stream, String toolChoice, ChatMessage... messages) {
        return new ChatCompletionRequest(MODEL, List.of(messages), stream, null, List.of(WEATHER_TOOL), toolChoice);
    }

    @Test
    void relaysAForcedToolCallAndCompletesTheRoundTripWithItsResult() {
        var first = adapter().invoke(request(false, "\"required\"", QUESTION));

        assertThat(first).as(String.valueOf(first)).isInstanceOf(ProviderResponse.Completion.class);
        var choice = ((ProviderResponse.Completion) first).response().choices().get(0);
        assertThat(choice.finishReason()).isEqualTo("tool_calls");
        ToolCall call = choice.message().toolCalls().get(0);
        assertThat(call.functionName()).isEqualTo("get_weather");
        assertThat(call.argumentsJson()).containsIgnoringCase("paris");

        var second = adapter().invoke(request(false, "\"none\"",
                QUESTION,
                new ChatMessage("assistant", "", List.of(call), null),
                new ChatMessage("tool", "{\"temperature_c\": 18, \"sky\": \"clear\"}", List.of(), call.id())));

        assertThat(second).as(String.valueOf(second)).isInstanceOf(ProviderResponse.Completion.class);
        var answer = ((ProviderResponse.Completion) second).response().choices().get(0);
        assertThat(answer.message().toolCalls()).isEmpty();
        assertThat(answer.message().content()).contains("18");
    }

    @Test
    void streamsAForcedToolCallAsOneCompleteDelta() {
        var chunks = JdkFlowAdapter.flowPublisherToFlux(adapter().invokeStreaming(request(true, "\"required\"", QUESTION)))
                .collectList()
                .block(Duration.ofMinutes(2));

        assertThat(chunks).isNotEmpty();
        List<ToolCallDelta> deltas = chunks.stream().flatMap(chunk -> chunk.toolCallDeltas().stream()).toList();
        assertThat(deltas).isNotEmpty();
        assertThat(deltas.get(0).id()).isNotBlank();
        assertThat(deltas.get(0).functionName()).isEqualTo("get_weather");
        assertThat(deltas.get(0).argumentsFragment()).containsIgnoringCase("paris");
        assertThat(chunks.getLast().finishReason()).isEqualTo("tool_calls");
    }
}
