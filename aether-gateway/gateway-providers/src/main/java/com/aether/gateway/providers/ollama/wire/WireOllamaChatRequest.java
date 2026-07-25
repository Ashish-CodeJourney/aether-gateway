package com.aether.gateway.providers.ollama.wire;

import java.util.List;

public record WireOllamaChatRequest(String model, List<WireOllamaMessage> messages, boolean stream) {
}
