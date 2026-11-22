package com.aether.gateway.core.domain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StreamChunkTest {

    @Test
    void aTextOnlyChunkCarriesNoToolCallDeltasAndNoExplicitFinishReason() {
        var chunk = new ProviderResponse.StreamChunk("id", 0, "hel", false);

        assertThat(chunk.toolCallDeltas()).isEmpty();
        assertThat(chunk.finishReason()).isNull();
    }

    @Test
    void aChunkCarriesIncrementalToolCallFragmentsAndTheProvidersFinishReason() {
        var opening = new ToolCallDelta(0, "call_1", "get_weather", "");
        var fragment = new ToolCallDelta(0, null, null, "{\"city\":");

        var chunk = new ProviderResponse.StreamChunk("id", 1, "", false, List.of(opening, fragment), null);
        var last = new ProviderResponse.StreamChunk("id", 2, "", true, List.of(), "tool_calls");

        assertThat(chunk.toolCallDeltas()).containsExactly(opening, fragment);
        assertThat(last.finishReason()).isEqualTo("tool_calls");
    }

    @Test
    void toolCallDeltasAreImmutableAfterConstruction() {
        var mutable = new ArrayList<ToolCallDelta>();
        mutable.add(new ToolCallDelta(0, "call_1", "get_weather", ""));
        var chunk = new ProviderResponse.StreamChunk("id", 0, "", false, mutable, null);

        mutable.add(new ToolCallDelta(1, "call_2", "get_time", ""));

        assertThat(chunk.toolCallDeltas()).hasSize(1);
    }
}
