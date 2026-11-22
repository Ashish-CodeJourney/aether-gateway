package com.aether.gateway.core.domain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmbeddingRequestTest {

    @Test
    void rejectsBlankModel() {
        assertThatThrownBy(() -> new EmbeddingRequest(" ", List.of("hello"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("model");
    }

    @Test
    void rejectsMissingInput() {
        assertThatThrownBy(() -> new EmbeddingRequest("embed", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("input");
    }

    @Test
    void rejectsEmptyInputList() {
        assertThatThrownBy(() -> new EmbeddingRequest("embed", List.of(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("input");
    }

    @Test
    void rejectsAnEmptyStringAmongTheInputs() {
        assertThatThrownBy(() -> new EmbeddingRequest("embed", List.of("ok", ""), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("input");
    }

    @Test
    void rejectsMoreThanTheMaximumAllowedInputCount() {
        List<String> tooMany = IntStream.range(0, EmbeddingRequest.MAX_INPUTS + 1).mapToObj(i -> "text " + i).toList();

        assertThatThrownBy(() -> new EmbeddingRequest("embed", tooMany, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(String.valueOf(EmbeddingRequest.MAX_INPUTS));
    }

    @Test
    void acceptsExactlyTheMaximumAllowedInputCount() {
        List<String> exactlyMax = IntStream.range(0, EmbeddingRequest.MAX_INPUTS).mapToObj(i -> "text " + i).toList();

        assertThat(new EmbeddingRequest("embed", exactlyMax, null).inputs()).hasSize(EmbeddingRequest.MAX_INPUTS);
    }

    @Test
    void rejectsNonPositiveDimensions() {
        assertThatThrownBy(() -> new EmbeddingRequest("embed", List.of("hello"), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dimensions");
    }

    @Test
    void isNotAffectedByLaterChangesToTheCallersInputList() {
        List<String> inputs = new ArrayList<>(List.of("hello"));
        var request = new EmbeddingRequest("embed", inputs, null);

        inputs.add("world");

        assertThat(request.inputs()).containsExactly("hello");
    }

    @Test
    void estimatesPromptTokensAsRoughlyFourCharactersPerTokenWithAtLeastOnePerInput() {
        var request = new EmbeddingRequest("embed", List.of("abcdefgh", "a"), null);

        assertThat(request.estimatedPromptTokens()).isEqualTo(3);
    }

    @Test
    void retargetsToAnUpstreamModelKeepingInputsAndDimensions() {
        var request = new EmbeddingRequest("alias", List.of("hello"), 256);

        var retargeted = request.withModel("text-embedding-3-small");

        assertThat(retargeted).isEqualTo(new EmbeddingRequest("text-embedding-3-small", List.of("hello"), 256));
    }
}
