package com.aether.gateway.core.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PromptReferenceTest {

    @Test
    void parsesAVersionReferenceWhenTheSuffixIsAnInteger() {
        PromptReference reference = PromptReference.parse("support-reply@2");

        assertThat(reference.promptName()).isEqualTo("support-reply");
        assertThat(reference.version()).contains(2);
        assertThat(reference.alias()).isEmpty();
    }

    @Test
    void parsesAnAliasReferenceWhenTheSuffixIsNotAnInteger() {
        PromptReference reference = PromptReference.parse("support-reply@production");

        assertThat(reference.promptName()).isEqualTo("support-reply");
        assertThat(reference.alias()).contains("production");
        assertThat(reference.version()).isEmpty();
    }

    @Test
    void rejectsAReferenceWithNoAtSeparator() {
        assertThatThrownBy(() -> PromptReference.parse("support-reply"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsABlankPromptName() {
        assertThatThrownBy(() -> PromptReference.parse("@production"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsABlankSuffix() {
        assertThatThrownBy(() -> PromptReference.parse("support-reply@"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
