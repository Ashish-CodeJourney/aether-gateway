package com.aether.gateway.providers.openai;

import com.aether.gateway.core.domain.ChatCompletionChoice;
import com.aether.gateway.core.domain.ChatCompletionRequest;
import com.aether.gateway.core.domain.ChatCompletionResponse;
import com.aether.gateway.core.domain.ChatMessage;
import com.aether.gateway.core.domain.ProviderResponse;
import com.aether.gateway.core.domain.ToolCall;
import com.aether.gateway.core.domain.ToolCallDelta;
import com.aether.gateway.core.domain.Usage;
import com.aether.gateway.providers.openai.wire.WireChatCompletionRequest;
import com.aether.gateway.providers.openai.wire.WireChatCompletionResponse;
import com.aether.gateway.providers.openai.wire.WireChatMessage;
import com.aether.gateway.providers.openai.wire.WireFunctionCall;
import com.aether.gateway.providers.openai.wire.WireStreamChunkResponse;
import com.aether.gateway.providers.openai.wire.WireToolCall;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * Domain to OpenAI chat-completions wire shape and back, shared by every
 * adapter that speaks that shape (the generic OpenAI-compatible adapter
 * and the mock provider's). Tool definitions, {@code tool_choice} and
 * tool-call arguments are relayed as opaque JSON, never reinterpreted.
 */
public final class OpenAiWireMapping {

    private static final JsonMapper RAW_JSON = JsonMapper.builder().build();

    private OpenAiWireMapping() {
    }

    public static WireChatCompletionRequest toWireRequest(ChatCompletionRequest request, boolean stream) {
        List<WireChatMessage> messages = request.messages().stream()
                .map(OpenAiWireMapping::toWireMessage)
                .toList();
        List<JsonNode> tools = request.tools().stream()
                .map(RAW_JSON::readTree)
                .toList();
        JsonNode toolChoice = request.toolChoice() == null ? null : RAW_JSON.readTree(request.toolChoice());
        return new WireChatCompletionRequest(request.model(), messages, stream, tools, toolChoice);
    }

    private static WireChatMessage toWireMessage(ChatMessage message) {
        List<WireToolCall> toolCalls = message.toolCalls().stream()
                .map(call -> new WireToolCall(null, call.id(), call.type(), new WireFunctionCall(call.functionName(), call.argumentsJson())))
                .toList();
        // OpenAI's own convention for an assistant turn that only calls tools.
        String content = message.content().isEmpty() && !toolCalls.isEmpty() ? null : message.content();
        return new WireChatMessage(message.role(), content, toolCalls, message.toolCallId());
    }

    public static ChatCompletionResponse toDomainResponse(WireChatCompletionResponse wire) {
        List<ChatCompletionChoice> choices = wire.choices().stream()
                .map(c -> new ChatCompletionChoice(c.index(), toDomainMessage(c.message()), c.finishReason()))
                .toList();
        Usage usage = Usage.of(wire.usage().promptTokens(), wire.usage().completionTokens());
        return new ChatCompletionResponse(wire.id(), wire.object(), wire.created(), wire.model(), choices, usage);
    }

    private static ChatMessage toDomainMessage(WireChatMessage wire) {
        List<ToolCall> toolCalls = wire.toolCalls() == null ? List.of() : wire.toolCalls().stream()
                .map(call -> new ToolCall(call.id(), call.type(), call.function().name(), call.function().arguments()))
                .toList();
        return new ChatMessage(wire.role(), wire.content() == null ? "" : wire.content(), toolCalls, wire.toolCallId());
    }

    public static ProviderResponse.StreamChunk toStreamChunk(WireStreamChunkResponse chunk, String responseId, int index) {
        var choice = chunk.choices().get(0);
        var delta = choice.delta();
        String content = delta != null && delta.content() != null ? delta.content() : "";
        List<ToolCallDelta> toolCallDeltas = delta == null || delta.toolCalls() == null ? List.of() : delta.toolCalls().stream()
                .map(OpenAiWireMapping::toToolCallDelta)
                .toList();
        return new ProviderResponse.StreamChunk(
                responseId, index, content, choice.finishReason() != null, toolCallDeltas, choice.finishReason());
    }

    private static ToolCallDelta toToolCallDelta(WireToolCall fragment) {
        WireFunctionCall function = fragment.function();
        return new ToolCallDelta(
                fragment.index() == null ? 0 : fragment.index(),
                fragment.id(),
                function == null ? null : function.name(),
                function == null ? null : function.arguments());
    }
}
