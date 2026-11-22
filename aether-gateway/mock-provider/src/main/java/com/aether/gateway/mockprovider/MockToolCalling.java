package com.aether.gateway.mockprovider;

import java.util.List;
import java.util.Optional;

/**
 * Deterministic tool-calling behaviour, driven purely by the request so
 * acceptance tests need no extra control surface: offered tools and no
 * tool result yet means "call the first tool"; a tool result as the last
 * turn means "answer in text, echoing that result" - which lets a test
 * observe that the result really travelled through the gateway to the
 * provider.
 */
public final class MockToolCalling {

    public record Call(String id, String name, String arguments) {
    }

    private MockToolCalling() {
    }

    public static Optional<Call> toolCallFor(List<String> offeredToolNames, String lastMessageRole) {
        if (offeredToolNames.isEmpty() || "tool".equals(lastMessageRole)) {
            return Optional.empty();
        }
        return Optional.of(new Call("call_mock_0", offeredToolNames.getFirst(), "{\"location\":\"mock-city\"}"));
    }

    public static String replyToToolResult(String model, String toolCallId, String result) {
        return "Mock response for model " + model + " using tool result for " + toolCallId + ": " + result;
    }
}
