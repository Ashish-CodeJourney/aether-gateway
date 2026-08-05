package com.aether.gateway.providers.anthropic.wire;

import java.util.List;

public record WireAnthropicResponse(
        String id,
        String type,
        String role,
        String model,
        List<WireAnthropicContentBlock> content,
        String stopReason,
        WireAnthropicUsage usage) {
}
