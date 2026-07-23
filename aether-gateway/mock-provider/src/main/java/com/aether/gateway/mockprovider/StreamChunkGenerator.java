package com.aether.gateway.mockprovider;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure logic: turns a canned response into a deterministic sequence of
 * streaming delta chunks, one word per chunk plus a final empty-delta
 * "stop" chunk, matching the OpenAI streaming chunk shape. Kept separate
 * from the web layer so chunk generation is unit-testable without
 * Reactor or Spring, per TESTING-STRATEGY.md's unit-test rule.
 */
public class StreamChunkGenerator {

    public record Chunk(String id, int index, String model, String deltaContent, String finishReason) {
    }

    public List<Chunk> generate(String id, String model, String text) {
        String[] words = text.split(" ");
        List<Chunk> chunks = new ArrayList<>();
        for (int i = 0; i < words.length; i++) {
            String content = i < words.length - 1 ? words[i] + " " : words[i];
            chunks.add(new Chunk(id, i, model, content, null));
        }
        chunks.add(new Chunk(id, words.length, model, "", "stop"));
        return chunks;
    }
}
