package com.aether.gateway.bench;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CorpusLoaderTest {

    @Test
    void loadsEveryLineOfAJsonlFileIntoACorpusEntry() throws IOException {
        Path fixture = Path.of("src/test/resources/fixtures/sample-corpus.jsonl");

        List<BenchmarkCorpusEntry> entries = CorpusLoader.load(fixture);

        assertThat(entries).hasSize(3);
    }

    @Test
    void parsesEveryFieldOfAVariantEntry() throws IOException {
        Path fixture = Path.of("src/test/resources/fixtures/sample-corpus.jsonl");

        List<BenchmarkCorpusEntry> entries = CorpusLoader.load(fixture);
        BenchmarkCorpusEntry variant = entries.get(1);

        assertThat(variant.id()).isEqualTo("fx-002");
        assertThat(variant.bucket()).isEqualTo("near-duplicate");
        assertThat(variant.pairId()).isEqualTo("pair-901");
        assertThat(variant.role()).isEqualTo("variant");
        assertThat(variant.prompt()).isEqualTo("Can you tell me the capital city of France?");
        assertThat(variant.expectedOutcome()).isEqualTo("hit");
    }

    @Test
    void leavesPairIdAndRoleNullWhenTheBucketHasNoPairing() throws IOException {
        Path fixture = Path.of("src/test/resources/fixtures/sample-corpus.jsonl");

        List<BenchmarkCorpusEntry> entries = CorpusLoader.load(fixture);
        BenchmarkCorpusEntry unrelated = entries.get(2);

        assertThat(unrelated.pairId()).isNull();
        assertThat(unrelated.role()).isNull();
        assertThat(unrelated.expectedOutcome()).isEqualTo("miss");
    }
}
