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

    /** {@code toolCall} is null on a text chunk. */
    public record Chunk(String id, int index, String model, String deltaContent, String finishReason, ToolCallFragment toolCall) {
        public Chunk(String id, int index, String model, String deltaContent, String finishReason) {
            this(id, index, model, deltaContent, finishReason, null);
        }
    }

    /** One {@code delta.tool_calls[0]} entry: id and name only on the opening fragment. */
    public record ToolCallFragment(String id, String name, String arguments) {
    }

    private static final int ARGUMENT_SLICE_LENGTH = 8;

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

    /** Splits the arguments into several slices, as real providers do, so clients' reassembly is genuinely exercised. */
    public List<Chunk> generateToolCall(String id, String model, MockToolCalling.Call call) {
        List<Chunk> chunks = new ArrayList<>();
        chunks.add(new Chunk(id, 0, model, null, null, new ToolCallFragment(call.id(), call.name(), "")));
        String arguments = call.arguments();
        for (int start = 0; start < arguments.length(); start += ARGUMENT_SLICE_LENGTH) {
            String slice = arguments.substring(start, Math.min(arguments.length(), start + ARGUMENT_SLICE_LENGTH));
            chunks.add(new Chunk(id, chunks.size(), model, null, null, new ToolCallFragment(null, null, slice)));
        }
        chunks.add(new Chunk(id, chunks.size(), model, null, "tool_calls"));
        return chunks;
    }
}
