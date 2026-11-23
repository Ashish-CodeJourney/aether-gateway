package com.aether.gateway.providers.ollama;

import com.aether.gateway.core.domain.ChatMessage;
import com.aether.gateway.core.domain.ToolCall;
import com.aether.gateway.core.domain.ToolCallDelta;
import com.aether.gateway.providers.ollama.wire.WireOllamaFunctionCall;
import com.aether.gateway.providers.ollama.wire.WireOllamaMessage;
import com.aether.gateway.providers.ollama.wire.WireOllamaToolCall;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * OpenAI-format tool calling to and from Ollama's {@code /api/chat}:
 *
 * <ul>
 *   <li>tool definitions are sent unchanged - Ollama takes OpenAI's
 *       {@code {"type":"function","function":{...}}} shape;</li>
 *   <li>{@code tool_choice} has no Ollama equivalent. "auto" (or none
 *       given) sends the tools; "none" honours the request by not offering
 *       them at all; "required" or a named function cannot be enforced, so
 *       the request is refused with {@code tool_choice_not_supported}
 *       rather than answered as if the model had been forced;</li>
 *   <li>tool call {@code arguments} are a JSON object on Ollama's wire and
 *       a JSON string in OpenAI's, converted both ways;</li>
 *   <li>a {@code role: "tool"} result carries the {@code tool_name}
 *       Ollama's docs use to identify it, recovered from the assistant
 *       call with the same id earlier in the conversation;</li>
 *   <li>a call without an {@code id} (older Ollama versions) gets a
 *       generated {@code call_<ordinal>_<name>}.</li>
 * </ul>
 */
final class OllamaToolTranslation {

    static final String TOOL_CHOICE_NOT_SUPPORTED = "tool_choice_not_supported";

    private static final JsonMapper RAW_JSON = JsonMapper.builder().build();

    private OllamaToolTranslation() {
    }

    /** Empty when the request's tool_choice can be honoured; otherwise the reason it cannot. */
    static Optional<String> unsupportedToolChoice(String providerName, String openAiToolChoice) {
        if (openAiToolChoice == null) {
            return Optional.empty();
        }
        JsonNode choice = RAW_JSON.readTree(openAiToolChoice);
        if (choice.isString() && List.of("auto", "none").contains(choice.asString())) {
            return Optional.empty();
        }
        return Optional.of("Provider '" + providerName + "' (Ollama) cannot force a tool call: only tool_choice \"auto\" and \"none\" "
                + "are supported, got " + openAiToolChoice + ". Use \"auto\", or route this model to a provider that supports forcing tools");
    }

    static List<JsonNode> tools(List<String> openAiTools, String openAiToolChoice) {
        if (openAiTools.isEmpty() || isNone(openAiToolChoice)) {
            return null;
        }
        return openAiTools.stream().map(RAW_JSON::readTree).toList();
    }

    private static boolean isNone(String openAiToolChoice) {
        return openAiToolChoice != null && "none".equals(RAW_JSON.readTree(openAiToolChoice).asString(null));
    }

    static List<WireOllamaMessage> messages(List<ChatMessage> conversation) {
        Map<String, String> functionNameByCallId = conversation.stream()
                .flatMap(message -> message.toolCalls().stream())
                .filter(call -> call.id() != null)
                .collect(Collectors.toMap(ToolCall::id, ToolCall::functionName, (first, second) -> second));
        return conversation.stream()
                .map(message -> new WireOllamaMessage(
                        message.role(),
                        message.content(),
                        message.toolCalls().isEmpty() ? null : message.toolCalls().stream().map(OllamaToolTranslation::toWire).toList(),
                        message.toolCallId() == null ? null : functionNameByCallId.get(message.toolCallId()),
                        message.toolCallId()))
                .toList();
    }

    private static WireOllamaToolCall toWire(ToolCall call) {
        JsonNode arguments = call.argumentsJson().isBlank() ? RAW_JSON.createObjectNode() : RAW_JSON.readTree(call.argumentsJson());
        return new WireOllamaToolCall(call.id(), "function", new WireOllamaFunctionCall(null, call.functionName(), arguments));
    }

    static ToolCall fromWire(int ordinal, WireOllamaToolCall call) {
        String name = call.function().name();
        String id = call.id() != null && !call.id().isBlank() ? call.id() : "call_" + ordinal + "_" + name;
        JsonNode arguments = call.function().arguments();
        return new ToolCall(id, "function", name, arguments == null || arguments.isNull() ? "{}" : arguments.toString());
    }

    /** Ollama sends each call whole, so one delta carries id, name and the complete arguments. */
    static ToolCallDelta deltaFromWire(int ordinal, WireOllamaToolCall wire) {
        ToolCall call = fromWire(ordinal, wire);
        return new ToolCallDelta(ordinal, call.id(), call.functionName(), call.argumentsJson());
    }
}
