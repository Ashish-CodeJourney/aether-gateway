package com.aether.gateway.proxy.web;

import com.aether.gateway.core.domain.ChatCompletionChoice;
import com.aether.gateway.core.domain.ChatCompletionRequest;
import com.aether.gateway.core.domain.ChatCompletionResponse;
import com.aether.gateway.core.domain.ChatMessage;
import com.aether.gateway.core.domain.ProviderResponse;
import com.aether.gateway.core.domain.Usage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChatCompletionDtoMapperTest {

    private final ChatCompletionDtoMapper mapper = new ChatCompletionDtoMapper();

    @Test
    void mapsAnInboundRequestDtoToTheDomainRequest() {
        var dto = new ChatCompletionRequestDto(
                "mock",
                List.of(new ChatMessageDto("user", "hello")),
                false);

        ChatCompletionRequest domain = mapper.toDomain(dto);

        assertThat(domain.model()).isEqualTo("mock");
        assertThat(domain.messages()).containsExactly(new ChatMessage("user", "hello"));
        assertThat(domain.stream()).isFalse();
    }

    @Test
    void defaultsStreamToFalseWhenAbsentFromTheRequest() {
        var dto = new ChatCompletionRequestDto("mock", List.of(new ChatMessageDto("user", "hi")), null);

        ChatCompletionRequest domain = mapper.toDomain(dto);

        assertThat(domain.stream()).isFalse();
    }

    @Test
    void mapsADomainResponseToTheOutboundDto() {
        var domainResponse = new ChatCompletionResponse(
                "id-1", "chat.completion", 1234L, "mock",
                List.of(new ChatCompletionChoice(0, new ChatMessage("assistant", "hi there"), "stop")),
                Usage.of(5, 3));

        ChatCompletionResponseDto dto = mapper.toDto(domainResponse);

        assertThat(dto.id()).isEqualTo("id-1");
        assertThat(dto.model()).isEqualTo("mock");
        assertThat(dto.choices()).hasSize(1);
        assertThat(dto.choices().get(0).message().content()).isEqualTo("hi there");
        assertThat(dto.usage().totalTokens()).isEqualTo(8);
    }

    @Test
    void mapsANonFinalStreamChunkWithNoFinishReason() {
        var chunk = new ProviderResponse.StreamChunk("resp-1", 0, "hel", false);

        StreamChunkResponseDto dto = mapper.toDto(chunk);

        assertThat(dto.id()).isEqualTo("resp-1");
        assertThat(dto.choices().get(0).delta().content()).isEqualTo("hel");
        assertThat(dto.choices().get(0).finishReason()).isNull();
    }

    @Test
    void mapsAFinalStreamChunkWithAStopFinishReason() {
        var chunk = new ProviderResponse.StreamChunk("resp-1", 2, "", true);

        StreamChunkResponseDto dto = mapper.toDto(chunk);

        assertThat(dto.choices().get(0).finishReason()).isEqualTo("stop");
    }
}
