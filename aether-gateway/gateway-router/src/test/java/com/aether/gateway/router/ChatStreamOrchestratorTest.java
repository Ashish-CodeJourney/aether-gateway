package com.aether.gateway.router;

import com.aether.gateway.core.domain.ChatCompletionRequest;
import com.aether.gateway.core.domain.ChatMessage;
import com.aether.gateway.core.domain.ProviderResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;

import static org.assertj.core.api.Assertions.assertThat;

class ChatStreamOrchestratorTest {

    @Test
    void delegatesToTheProviderCallerAndReturnsItsPublisherUnchanged() {
        Flow.Publisher<ProviderResponse.StreamChunk> stubPublisher = new SubmissionPublisher<>();
        var request = new ChatCompletionRequest("mock", List.of(new ChatMessage("user", "hi")), true);
        StreamingProviderCaller stubCaller = req -> {
            assertThat(req).isEqualTo(request);
            return stubPublisher;
        };
        var orchestrator = new ChatStreamOrchestrator(stubCaller);

        Flow.Publisher<ProviderResponse.StreamChunk> result = orchestrator.stream(request);

        assertThat(result).isSameAs(stubPublisher);
    }
}
