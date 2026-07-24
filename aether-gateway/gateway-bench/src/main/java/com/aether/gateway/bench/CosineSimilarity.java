package com.aether.gateway.bench;

/** Pure cosine similarity between two equal-length embedding vectors. */
public final class CosineSimilarity {

    private CosineSimilarity() {
    }

    public static double of(float[] a, float[] b) {
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
