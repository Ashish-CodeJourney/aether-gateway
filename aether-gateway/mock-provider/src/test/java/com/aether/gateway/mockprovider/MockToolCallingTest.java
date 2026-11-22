package com.aether.gateway.mockprovider;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MockToolCallingTest {

    @Test
    void callsTheFirstOfferedToolWhenTheConversationHasNoToolResultYet() {
        var call = MockToolCalling.toolCallFor(List.of("get_weather", "get_time"), "user");

        assertThat(call).hasValue(new MockToolCalling.Call("call_mock_0", "get_weather", "{\"location\":\"mock-city\"}"));
    }

    @Test
    void answersInTextOnceTheLastTurnIsAToolResult() {
        assertThat(MockToolCalling.toolCallFor(List.of("get_weather"), "tool")).isEmpty();
    }

    @Test
    void answersInTextWhenNoToolsAreOffered() {
        assertThat(MockToolCalling.toolCallFor(List.of(), "user")).isEmpty();
    }

    @Test
    void echoesTheToolResultItReceivedSoTheRoundTripIsObservable() {
        assertThat(MockToolCalling.replyToToolResult("mock", "call_mock_0", "18C and sunny"))
                .isEqualTo("Mock response for model mock using tool result for call_mock_0: 18C and sunny");
    }

    @Test
    void streamsACallAsAnOpeningFragmentArgumentSlicesAndAToolCallsFinish() {
        var call = new MockToolCalling.Call("call_mock_0", "get_weather", "{\"location\":\"mock-city\"}");

        List<StreamChunkGenerator.Chunk> chunks = new StreamChunkGenerator().generateToolCall("resp-1", "mock", call);

        assertThat(chunks.get(0).toolCall()).isEqualTo(new StreamChunkGenerator.ToolCallFragment("call_mock_0", "get_weather", ""));
        String arguments = chunks.subList(1, chunks.size() - 1).stream()
                .map(chunk -> chunk.toolCall().arguments())
                .reduce("", String::concat);
        assertThat(arguments).isEqualTo("{\"location\":\"mock-city\"}");
        assertThat(chunks.subList(1, chunks.size() - 1)).hasSizeGreaterThan(1)
                .allSatisfy(chunk -> assertThat(chunk.toolCall().id()).isNull());
        assertThat(chunks.getLast().finishReason()).isEqualTo("tool_calls");
        assertThat(chunks).extracting(StreamChunkGenerator.Chunk::index).doesNotHaveDuplicates();
    }
}
