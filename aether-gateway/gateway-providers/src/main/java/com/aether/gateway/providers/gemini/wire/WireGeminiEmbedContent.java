package com.aether.gateway.providers.gemini.wire;

import java.util.List;

/** Like {@link WireGeminiContent} but without a role, which embedding requests do not carry. */
public record WireGeminiEmbedContent(List<WireGeminiPart> parts) {
}
