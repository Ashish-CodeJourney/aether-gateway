package com.aether.gateway.providers.anthropic;

import com.aether.gateway.core.domain.ChatMessage;
import com.aether.gateway.core.domain.ToolCall;
import com.aether.gateway.providers.anthropic.wire.WireAnthropicMessage;
import com.aether.gateway.providers.anthropic.wire.WireAnthropicRequestBlock;
import com.aether.gateway.providers.anthropic.wire.WireAnthropicTool;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * OpenAI-format conversation and tool payloads to Anthropic's Messages
 * API shape:
 *
 * <ul>
 *   <li>a tool definition's {@code function.parameters} becomes
 *       {@code input_schema} (required by Anthropic, so an absent one
 *       becomes an empty object schema);</li>
 *   <li>{@code tool_choice} "auto"/"required"/"none"/a named function map
 *       to {@code auto}/{@code any}/{@code none}/{@code tool};</li>
 *   <li>an assistant turn's {@code tool_calls} become {@code tool_use}
 *       blocks, with the arguments string parsed back into the JSON
 *       object Anthropic expects as {@code input};</li>
 *   <li>{@code role: "tool"} turns become {@code tool_result} blocks in a
 *       user turn, with consecutive results merged into one turn: the API
 *       requires every result for an assistant turn's calls to arrive
 *       together in the very next user message.</li>
 * </ul>
 */
final class AnthropicRequestTranslation {

    private static final JsonMapper RAW_JSON = JsonMapper.builder().build();

    private AnthropicRequestTranslation() {
    }

    static List<WireAnthropicTool> tools(List<String> openAiTools) {
        if (openAiTools.isEmpty()) {
            return null;
        }
        return openAiTools.stream()
                .map(RAW_JSON::readTree)
                .map(tool -> tool.path("function"))
                .map(function -> new WireAnthropicTool(
                        function.path("name").asString(),
                        function.hasNonNull("description") ? function.get("description").asString() : null,
                        function.hasNonNull("parameters") ? function.get("parameters") : emptyObjectSchema()))
                .toList();
    }

    private static JsonNode emptyObjectSchema() {
        ObjectNode schema = RAW_JSON.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties");
        return schema;
    }

    static JsonNode toolChoice(String openAiToolChoice) {
        if (openAiToolChoice == null) {
            return null;
        }
        JsonNode choice = RAW_JSON.readTree(openAiToolChoice);
        ObjectNode translated = RAW_JSON.createObjectNode();
        if (choice.isString()) {
            String type = switch (choice.asString()) {
                case "required" -> "any";
                case "none" -> "none";
                default -> "auto";
            };
            return translated.put("type", type);
        }
        String forcedName = choice.path("function").path("name").asString(null);
        if (forcedName == null) {
            return translated.put("type", "auto");
        }
        return translated.put("type", "tool").put("name", forcedName);
    }

    static List<WireAnthropicMessage> messages(List<ChatMessage> conversation) {
        List<WireAnthropicMessage> translated = new ArrayList<>();
        for (ChatMessage message : conversation) {
            if ("system".equals(message.role())) {
                continue;
            }
            if ("tool".equals(message.role())) {
                appendToolResult(translated, WireAnthropicRequestBlock.toolResult(message.toolCallId(), message.content()));
                continue;
            }
            translated.add(toWireMessage(message));
        }
        return List.copyOf(translated);
    }

    private static void appendToolResult(List<WireAnthropicMessage> translated, WireAnthropicRequestBlock result) {
        if (!translated.isEmpty() && isToolResultTurn(translated.getLast())) {
            var previous = translated.removeLast();
            var blocks = Stream.concat(blocksOf(previous).stream(), Stream.of(result)).toList();
            translated.add(new WireAnthropicMessage("user", blocks));
            return;
        }
        translated.add(new WireAnthropicMessage("user", List.of(result)));
    }

    private static boolean isToolResultTurn(WireAnthropicMessage message) {
        return "user".equals(message.role())
                && message.content() instanceof List<?> blocks
                && blocks.stream().allMatch(block -> block instanceof WireAnthropicRequestBlock b && "tool_result".equals(b.type()));
    }

    private static List<WireAnthropicRequestBlock> blocksOf(WireAnthropicMessage message) {
        return ((List<?>) message.content()).stream()
                .map(WireAnthropicRequestBlock.class::cast)
                .toList();
    }

    private static WireAnthropicMessage toWireMessage(ChatMessage message) {
        if (message.toolCalls().isEmpty()) {
            return new WireAnthropicMessage(message.role(), message.content());
        }
        Stream<WireAnthropicRequestBlock> text = message.content().isEmpty()
                ? Stream.empty()
                : Stream.of(WireAnthropicRequestBlock.text(message.content()));
        Stream<WireAnthropicRequestBlock> toolUses = message.toolCalls().stream()
                .map(AnthropicRequestTranslation::toToolUse);
        return new WireAnthropicMessage(message.role(), Stream.concat(text, toolUses).toList());
    }

    private static WireAnthropicRequestBlock toToolUse(ToolCall call) {
        JsonNode input = call.argumentsJson().isBlank() ? RAW_JSON.createObjectNode() : RAW_JSON.readTree(call.argumentsJson());
        return WireAnthropicRequestBlock.toolUse(call.id(), call.functionName(), input);
    }
}
