package com.aether.gateway.core.domain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatMessageTest {

    private static ToolCall weatherCall() {
        return new ToolCall("call_1", "function", "get_weather", "{\"city\":\"Paris\"}");
    }

    @Test
    void aPlainMessageCarriesNoToolCallsAndNoToolCallId() {
        var message = new ChatMessage("user", "hello");

        assertThat(message.toolCalls()).isEmpty();
        assertThat(message.toolCallId()).isNull();
    }

    @Test
    void anAssistantMessageCarriesItsToolCalls() {
        var message = new ChatMessage("assistant", "", List.of(weatherCall()), null);

        assertThat(message.toolCalls()).containsExactly(weatherCall());
    }

    @Test
    void toolCallsAreImmutableAfterConstruction() {
        var mutable = new ArrayList<ToolCall>();
        mutable.add(weatherCall());
        var message = new ChatMessage("assistant", "", mutable, null);

        mutable.add(weatherCall());

        assertThat(message.toolCalls()).hasSize(1);
    }

    @Test
    void aToolResultMessageCarriesTheIdOfTheCallItAnswers() {
        var message = new ChatMessage("tool", "18C and sunny", List.of(), "call_1");

        assertThat(message.toolCallId()).isEqualTo("call_1");
    }

    @Test
    void rejectsAToolResultMessageWithoutAToolCallId() {
        assertThatThrownBy(() -> new ChatMessage("tool", "18C and sunny", List.of(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tool_call_id");
    }

    @Test
    void hasToolContentIsTrueOnlyForToolCallsOrToolResults() {
        assertThat(new ChatMessage("user", "hi").hasToolContent()).isFalse();
        assertThat(new ChatMessage("assistant", "", List.of(weatherCall()), null).hasToolContent()).isTrue();
        assertThat(new ChatMessage("tool", "18C", List.of(), "call_1").hasToolContent()).isTrue();
    }

    @Test
    void aToolCallRequiresAFunctionName() {
        assertThatThrownBy(() -> new ToolCall("call_1", "function", " ", "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    void aToolCallDefaultsItsTypeToFunctionAndItsArgumentsToAnEmptyObject() {
        var call = new ToolCall("call_1", null, "get_weather", null);

        assertThat(call.type()).isEqualTo("function");
        assertThat(call.argumentsJson()).isEqualTo("{}");
    }
}
