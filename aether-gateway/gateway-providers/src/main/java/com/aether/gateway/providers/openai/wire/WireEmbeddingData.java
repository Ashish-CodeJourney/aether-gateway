package com.aether.gateway.providers.openai.wire;

import java.util.List;

public record WireEmbeddingData(String object, int index, List<Float> embedding) {
}
