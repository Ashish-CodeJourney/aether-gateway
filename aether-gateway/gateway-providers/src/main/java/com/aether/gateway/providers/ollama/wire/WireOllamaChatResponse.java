package com.aether.gateway.providers.ollama.wire;

/**
 * Shape of both Ollama's non-streaming {@code /api/chat} response and
 * each line of its NDJSON streaming response - Ollama reuses this exact
 * envelope per streamed line, with {@code done=false} until the final
 * line, matching the real Ollama API (not modelled on OpenAI's
 * delta/chunk shape at all, deliberately: this is what makes it a real
 * second wire format for the contract suite to prove against, not a
 * relabelled copy of the OpenAI one).
 */
public record WireOllamaChatResponse(
        String model, WireOllamaMessage message, boolean done, Integer promptEvalCount, Integer evalCount) {
}
