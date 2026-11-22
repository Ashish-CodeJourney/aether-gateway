package com.aether.gateway.providers.openai.wire;

import java.util.List;

public record WireDelta(String content, List<WireToolCall> toolCalls) {
}
