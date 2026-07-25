package com.aether.gateway.admin.web;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** PRD 13.2: {@code POST /admin/prompts/{name}/versions}. */
public record CreatePromptVersionRequestDto(List<PromptTemplateMessageDto> template, Set<String> variables, Map<String, Object> modelDefaults) {

    public record PromptTemplateMessageDto(String role, String content) {
    }
}
