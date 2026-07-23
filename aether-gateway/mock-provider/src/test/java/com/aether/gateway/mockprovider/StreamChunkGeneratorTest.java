package com.aether.gateway.mockprovider;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StreamChunkGeneratorTest {

    private final StreamChunkGenerator generator = new StreamChunkGenerator();

    @Test
    void generatesOneChunkPerWordPlusAFinalStopChunk() {
        List<StreamChunkGenerator.Chunk> chunks = generator.generate("resp-1", "mock", "hello there friend");

        // 3 words + 1 final stop chunk with empty delta
        assertThat(chunks).hasSize(4);
        assertThat(chunks.get(0).deltaContent()).isEqualTo("hello ");
        assertThat(chunks.get(1).deltaContent()).isEqualTo("there ");
        assertThat(chunks.get(2).deltaContent()).isEqualTo("friend");
        assertThat(chunks.get(3).deltaContent()).isEmpty();
    }

    @Test
    void onlyTheFinalChunkCarriesAFinishReason() {
        List<StreamChunkGenerator.Chunk> chunks = generator.generate("resp-1", "mock", "one two");

        assertThat(chunks.get(0).finishReason()).isNull();
        assertThat(chunks.get(1).finishReason()).isNull();
        assertThat(chunks.get(2).finishReason()).isEqualTo("stop");
    }

    @Test
    void everyChunkSharesTheSameResponseIdAndModel() {
        List<StreamChunkGenerator.Chunk> chunks = generator.generate("resp-42", "gpt-mock", "a b");

        assertThat(chunks).allSatisfy(c -> {
            assertThat(c.id()).isEqualTo("resp-42");
            assertThat(c.model()).isEqualTo("gpt-mock");
        });
    }

    @Test
    void chunksAreSequentiallyIndexed() {
        List<StreamChunkGenerator.Chunk> chunks = generator.generate("resp-1", "mock", "a b c");

        assertThat(chunks).extracting(StreamChunkGenerator.Chunk::index)
                .containsExactly(0, 1, 2, 3);
    }
}
