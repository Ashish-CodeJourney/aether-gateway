package com.aether.gateway.core.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** F7.6: strict-variable-validated {{placeholder}} substitution into a prompt template. */
public final class PromptTemplateRenderer {

    private PromptTemplateRenderer() {
    }

    public static List<ChatMessage> render(List<ChatMessage> template, Set<String> declaredVariables, Map<String, String> providedVariables) {
        Set<String> missing = new HashSet<>(declaredVariables);
        missing.removeAll(providedVariables.keySet());
        Set<String> unexpected = new HashSet<>(providedVariables.keySet());
        unexpected.removeAll(declaredVariables);
        if (!missing.isEmpty() || !unexpected.isEmpty()) {
            throw new PromptVariableValidationException(missing, unexpected);
        }

        List<ChatMessage> rendered = new ArrayList<>();
        for (ChatMessage message : template) {
            String content = message.content();
            for (Map.Entry<String, String> variable : providedVariables.entrySet()) {
                content = content.replace("{{" + variable.getKey() + "}}", variable.getValue());
            }
            rendered.add(new ChatMessage(message.role(), content));
        }
        return rendered;
    }
}
