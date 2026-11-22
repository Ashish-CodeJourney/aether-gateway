package com.aether.gateway.core.domain;

import java.util.List;

/** One input's vector; {@code index} is the input's position in the request. */
public record Embedding(int index, List<Float> vector) {
    public Embedding {
        vector = List.copyOf(vector);
    }
}
