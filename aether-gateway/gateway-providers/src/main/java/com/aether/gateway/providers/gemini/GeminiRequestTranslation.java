package com.aether.gateway.providers.gemini;

import com.aether.gateway.core.domain.ChatMessage;
import com.aether.gateway.core.domain.ToolCall;
import com.aether.gateway.providers.gemini.wire.WireGeminiContent;
import com.aether.gateway.providers.gemini.wire.WireGeminiFunctionCall;
import com.aether.gateway.providers.gemini.wire.WireGeminiFunctionCallingConfig;
import com.aether.gateway.providers.gemini.wire.WireGeminiFunctionDeclaration;
import com.aether.gateway.providers.gemini.wire.WireGeminiFunctionResponse;
import com.aether.gateway.providers.gemini.wire.WireGeminiPart;
import com.aether.gateway.providers.gemini.wire.WireGeminiTool;
import com.aether.gateway.providers.gemini.wire.WireGeminiToolConfig;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * OpenAI-format conversation and tool payloads to the Gemini API's
 * {@code generateContent} shape:
 *
 * <ul>
 *   <li>tool definitions become one {@code tools} entry holding every
 *       {@code functionDeclarations} item, with each JSON schema converted
 *       to Gemini's OpenAPI-subset Schema by {@link GeminiSchemaConversion};</li>
 *   <li>{@code tool_choice} "auto"/"none"/"required"/a named function map
 *       to {@code functionCallingConfig.mode} AUTO/NONE/ANY/ANY with
 *       {@code allowedFunctionNames};</li>
 *   <li>an assistant turn's {@code tool_calls} become {@code functionCall}
 *       parts of a {@code model} turn, arguments parsed back into the JSON
 *       object Gemini expects as {@code args};</li>
 *   <li>{@code role: "tool"} turns become {@code functionResponse} parts,
 *       consecutive ones merged into one user turn because Gemini expects
 *       every response to a model turn's calls together. Gemini matches a
 *       response to its call by function name, which an OpenAI tool
 *       message does not carry, so it is recovered from the assistant
 *       tool call with the same id earlier in the conversation.</li>
 * </ul>
 *
 * <p>Gemini 3 models reject a replayed function call that lacks the
 * {@code thoughtSignature} the model attached to it, and an OpenAI-format
 * conversation has nowhere to carry that signature back. Each replayed
 * model turn's first function call is therefore sent with Google's
 * documented {@code skip_thought_signature_validator} placeholder, the
 * escape hatch for function calls the API did not itself generate.
 */
final class GeminiRequestTranslation {

    static final String SKIP_THOUGHT_SIGNATURE = "skip_thought_signature_validator";

    private static final JsonMapper RAW_JSON = JsonMapper.builder().build();

    private GeminiRequestTranslation() {
    }

    /** A {@code role: "tool"} message whose id matches no assistant tool call, so its function name cannot be recovered. */
    static final class UnknownToolCallIdException extends IllegalArgumentException {
        UnknownToolCallIdException(String toolCallId) {
            super("tool message answers tool_call_id '" + toolCallId + "', but no earlier assistant message made a tool call with that id; "
                    + "Gemini needs the function name, which is recovered from that call");
        }
    }

    static List<WireGeminiTool> tools(List<String> openAiTools) {
        if (openAiTools.isEmpty()) {
            return null;
        }
        List<WireGeminiFunctionDeclaration> declarations = openAiTools.stream()
                .map(RAW_JSON::readTree)
                .map(tool -> tool.path("function"))
                .map(function -> new WireGeminiFunctionDeclaration(
                        function.path("name").asString(),
                        function.hasNonNull("description") ? function.get("description").asString() : null,
                        GeminiSchemaConversion.parameters(function.get("parameters"))))
                .toList();
        return List.of(new WireGeminiTool(declarations));
    }

    static WireGeminiToolConfig toolConfig(String openAiToolChoice) {
        if (openAiToolChoice == null) {
            return null;
        }
        JsonNode choice = RAW_JSON.readTree(openAiToolChoice);
        if (choice.isString()) {
            String mode = switch (choice.asString()) {
                case "none" -> "NONE";
                case "required" -> "ANY";
                default -> "AUTO";
            };
            return new WireGeminiToolConfig(new WireGeminiFunctionCallingConfig(mode, null));
        }
        String forcedName = choice.path("function").path("name").asString(null);
        if (forcedName == null) {
            return new WireGeminiToolConfig(new WireGeminiFunctionCallingConfig("AUTO", null));
        }
        return new WireGeminiToolConfig(new WireGeminiFunctionCallingConfig("ANY", List.of(forcedName)));
    }

    static List<WireGeminiContent> contents(List<ChatMessage> conversation) {
        Map<String, String> functionNameByCallId = conversation.stream()
                .flatMap(message -> message.toolCalls().stream())
                .filter(call -> call.id() != null)
                .collect(Collectors.toMap(ToolCall::id, ToolCall::functionName, (first, second) -> second));

        List<WireGeminiContent> translated = new ArrayList<>();
        for (ChatMessage message : conversation) {
            switch (message.role()) {
                case "system" -> {
                }
                case "tool" -> appendFunctionResponse(translated, functionResponse(message, functionNameByCallId));
                case "assistant" -> translated.add(new WireGeminiContent("model", modelParts(message)));
                default -> translated.add(new WireGeminiContent("user", List.of(new WireGeminiPart(message.content()))));
            }
        }
        return List.copyOf(translated);
    }

    private static WireGeminiPart functionResponse(ChatMessage message, Map<String, String> functionNameByCallId) {
        String name = functionNameByCallId.get(message.toolCallId());
        if (name == null) {
            throw new UnknownToolCallIdException(message.toolCallId());
        }
        return WireGeminiPart.functionResponse(new WireGeminiFunctionResponse(name, responseObject(message.content())));
    }

    /** Gemini requires an object; a non-object result is wrapped under "output", the key its docs name for a function's output. */
    private static JsonNode responseObject(String content) {
        try {
            JsonNode parsed = RAW_JSON.readTree(content);
            if (parsed != null && parsed.isObject()) {
                return parsed;
            }
        } catch (JacksonException notJson) {
            // fall through: plain-text results are the common case
        }
        ObjectNode wrapped = RAW_JSON.createObjectNode();
        wrapped.put("output", content);
        return wrapped;
    }

    private static void appendFunctionResponse(List<WireGeminiContent> translated, WireGeminiPart response) {
        if (!translated.isEmpty() && isFunctionResponseTurn(translated.getLast())) {
            var previous = translated.removeLast();
            translated.add(new WireGeminiContent("user", Stream.concat(previous.parts().stream(), Stream.of(response)).toList()));
            return;
        }
        translated.add(new WireGeminiContent("user", List.of(response)));
    }

    private static boolean isFunctionResponseTurn(WireGeminiContent content) {
        return "user".equals(content.role()) && content.parts().stream().allMatch(part -> part.functionResponse() != null);
    }

    private static List<WireGeminiPart> modelParts(ChatMessage message) {
        if (message.toolCalls().isEmpty()) {
            return List.of(new WireGeminiPart(message.content()));
        }
        Stream<WireGeminiPart> text = message.content().isEmpty() ? Stream.empty() : Stream.of(new WireGeminiPart(message.content()));
        List<ToolCall> calls = message.toolCalls();
        Stream<WireGeminiPart> functionCalls = IntStream.range(0, calls.size())
                .mapToObj(i -> WireGeminiPart.functionCall(
                        new WireGeminiFunctionCall(null, calls.get(i).functionName(), args(calls.get(i))),
                        i == 0 ? SKIP_THOUGHT_SIGNATURE : null));
        return Stream.concat(text, functionCalls).toList();
    }

    private static JsonNode args(ToolCall call) {
        return call.argumentsJson().isBlank() ? RAW_JSON.createObjectNode() : RAW_JSON.readTree(call.argumentsJson());
    }
}
