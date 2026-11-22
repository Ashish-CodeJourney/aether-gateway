package com.aether.gateway.proxy.web;

import com.aether.gateway.core.domain.Embedding;
import com.aether.gateway.core.domain.EmbeddingRequest;
import com.aether.gateway.core.domain.EmbeddingResponse;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/** Where embeddings DTOs and domain types cross; also owns request validation that is about the wire shape rather than the domain. */
final class EmbeddingsDtoMapper {

    private static final Set<String> ENCODING_FORMATS = Set.of("float", "base64");

    /** A client error tied to one request field, reported as OpenAI's {@code error.param}. */
    static final class InvalidEmbeddingRequestException extends RuntimeException {
        final String param;

        InvalidEmbeddingRequestException(String param, String message) {
            super(message, null, false, false);
            this.param = param;
        }
    }

    private EmbeddingsDtoMapper() {
    }

    static boolean wantsBase64(EmbeddingsRequestDto dto) {
        return "base64".equals(dto.encodingFormat());
    }

    static EmbeddingRequest toDomain(EmbeddingsRequestDto dto) {
        if (dto.encodingFormat() != null && !ENCODING_FORMATS.contains(dto.encodingFormat())) {
            throw new InvalidEmbeddingRequestException("encoding_format",
                    "encoding_format must be 'float' or 'base64' (got '" + dto.encodingFormat() + "')");
        }
        return new EmbeddingRequest(dto.model(), inputsOf(dto.input()), dto.dimensions());
    }

    private static List<String> inputsOf(Object input) {
        if (input == null) {
            throw new InvalidEmbeddingRequestException("input", "input is required: a string or an array of strings");
        }
        if (input instanceof String text) {
            return List.of(text);
        }
        if (input instanceof List<?> items) {
            if (items.stream().anyMatch(item -> item instanceof Number || item instanceof List<?>)) {
                throw new InvalidEmbeddingRequestException("input",
                        "token-id arrays are not supported: this gateway routes across providers with different tokenizers, "
                                + "so send the text itself as a string or an array of strings");
            }
            if (items.stream().allMatch(String.class::isInstance)) {
                return items.stream().map(String.class::cast).toList();
            }
        }
        throw new InvalidEmbeddingRequestException("input", "input must be a string or an array of strings");
    }

    static EmbeddingsResponseDto toDto(EmbeddingResponse response, boolean base64) {
        List<EmbeddingsResponseDto.Item> data = response.data().stream()
                .map(embedding -> new EmbeddingsResponseDto.Item(
                        "embedding", embedding.index(), base64 ? toBase64(embedding) : embedding.vector()))
                .toList();
        return new EmbeddingsResponseDto("list", data, response.model(),
                new EmbeddingsResponseDto.Usage(response.promptTokens(), response.promptTokens()));
    }

    /** OpenAI's base64 format: the vector's raw little-endian float32 bytes. */
    private static String toBase64(Embedding embedding) {
        ByteBuffer bytes = ByteBuffer.allocate(embedding.vector().size() * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        embedding.vector().forEach(bytes::putFloat);
        return Base64.getEncoder().encodeToString(bytes.array());
    }
}
