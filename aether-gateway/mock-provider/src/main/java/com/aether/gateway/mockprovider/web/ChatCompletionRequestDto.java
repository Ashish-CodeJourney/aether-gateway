package com.aether.gateway.mockprovider.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.JsonNode;

import java.util.List;

/** {@code tools} is read only for each tool's {@code function.name}; the rest of the definition is irrelevant to a mock. */
public record ChatCompletionRequestDto(
        String model, List<ChatMessageDto> messages, Boolean stream, List<JsonNode> tools,
        @JsonProperty("stream_options") StreamOptionsDto streamOptions) {

    public List<String> toolNames() {
        return tools == null ? List.of() : tools.stream()
                .map(tool -> tool.path("function").path("name").asString())
                .toList();
    }

    public boolean includeStreamUsage() {
        return streamOptions != null && Boolean.TRUE.equals(streamOptions.includeUsage());
    }
}
