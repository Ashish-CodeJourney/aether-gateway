package com.aether.gateway.core.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PromptTemplateRendererTest {

    @Test
    void substitutesEveryDeclaredVariableIntoTheTemplate() {
        List<ChatMessage> template = List.of(new ChatMessage("user", "Hello {{name}}, welcome to {{place}}."));

        List<ChatMessage> rendered = PromptTemplateRenderer.render(
                template, Set.of("name", "place"), Map.of("name", "Ada", "place", "London"));

        assertThat(rendered).containsExactly(new ChatMessage("user", "Hello Ada, welcome to London."));
    }

    @Test
    void substitutesTheSameVariableMultipleTimesAndAcrossMultipleMessages() {
        List<ChatMessage> template = List.of(
                new ChatMessage("system", "You are helping {{name}}."),
                new ChatMessage("user", "{{name}}, please respond."));

        List<ChatMessage> rendered = PromptTemplateRenderer.render(template, Set.of("name"), Map.of("name", "Bo"));

        assertThat(rendered).containsExactly(
                new ChatMessage("system", "You are helping Bo."),
                new ChatMessage("user", "Bo, please respond."));
    }

    @Test
    void rejectsARenderMissingARequiredVariable() {
        List<ChatMessage> template = List.of(new ChatMessage("user", "Hello {{name}}."));

        assertThatThrownBy(() -> PromptTemplateRenderer.render(template, Set.of("name"), Map.of()))
                .isInstanceOf(PromptVariableValidationException.class)
                .hasMessageContaining("name");
    }

    @Test
    void rejectsARenderWithAnUnexpectedVariable() {
        List<ChatMessage> template = List.of(new ChatMessage("user", "Hello {{name}}."));

        assertThatThrownBy(() -> PromptTemplateRenderer.render(template, Set.of("name"), Map.of("name", "Ada", "extra", "oops")))
                .isInstanceOf(PromptVariableValidationException.class)
                .hasMessageContaining("extra");
    }

    @Test
    void acceptsATemplateWithNoDeclaredVariables() {
        List<ChatMessage> template = List.of(new ChatMessage("user", "Hello there."));

        List<ChatMessage> rendered = PromptTemplateRenderer.render(template, Set.of(), Map.of());

        assertThat(rendered).containsExactly(new ChatMessage("user", "Hello there."));
    }
}
