package com.aether.gateway.mockprovider;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import java.util.stream.IntStream;

/**
 * Embeddings that are meaningless but repeatable: each text seeds its own
 * random stream, so the same text always yields the same unit vector
 * (across processes and restarts) and tests can assert on exact values.
 */
public class DeterministicEmbeddingGenerator {

    public static final int DEFAULT_DIMENSIONS = 16;

    public List<Float> embed(String text, Integer dimensions) {
        int size = dimensions != null ? dimensions : DEFAULT_DIMENSIONS;
        SplittableRandom random = new SplittableRandom(seedFor(text));
        double[] raw = IntStream.range(0, size).mapToDouble(i -> random.nextDouble(-1.0, 1.0)).toArray();
        double length = Math.sqrt(Arrays.stream(raw).map(v -> v * v).sum());
        return Arrays.stream(raw).mapToObj(v -> (float) (v / length)).toList();
    }

    /** Not String.hashCode alone: that collides for short strings far too easily to make distinct inputs reliably distinct. */
    private static long seedFor(String text) {
        long hash = 0xcbf29ce484222325L;
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            hash ^= b;
            hash *= 0x100000001b3L;
        }
        return hash;
    }
}
