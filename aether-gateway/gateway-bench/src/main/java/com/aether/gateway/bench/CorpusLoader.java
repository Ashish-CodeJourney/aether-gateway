package com.aether.gateway.bench;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Reads a `benchmark/corpus/*.jsonl` file into {@link BenchmarkCorpusEntry} rows, one per line. */
public final class CorpusLoader {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private CorpusLoader() {
    }

    public static List<BenchmarkCorpusEntry> load(Path jsonlFile) throws IOException {
        List<BenchmarkCorpusEntry> entries = new ArrayList<>();
        for (String line : Files.readAllLines(jsonlFile, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            entries.add(toEntry(MAPPER.readTree(line)));
        }
        return entries;
    }

    private static BenchmarkCorpusEntry toEntry(JsonNode node) {
        return new BenchmarkCorpusEntry(
                node.path("id").asString(null),
                node.path("bucket").asString(null),
                node.path("pair_id").asString(null),
                node.path("role").asString(null),
                node.path("prompt").asString(null),
                node.path("expected_outcome").asString(null));
    }
}
