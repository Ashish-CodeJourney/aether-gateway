package com.aether.gateway.router;

import com.aether.gateway.core.domain.ChatCompletionChoice;
import com.aether.gateway.core.domain.ChatCompletionRequest;
import com.aether.gateway.core.domain.ChatCompletionResponse;
import com.aether.gateway.core.domain.ChatMessage;
import com.aether.gateway.core.domain.ProviderResponse;
import com.aether.gateway.core.domain.Usage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChatCompletionOrchestratorTest {

    @Test
    void forwardsARequestAndReturnsTheProvidersCompletion() {
        var expectedResponse = new ChatCompletionResponse(
                "mock-1", "chat.completion", 0L, "mock",
                List.of(new ChatCompletionChoice(0, new ChatMessage("assistant", "hi"), "stop")),
                Usage.of(3, 2));
        ProviderCaller stubCaller = request -> new ProviderResponse.Completion(expectedResponse);
        var orchestrator = new ChatCompletionOrchestrator(stubCaller);

        var request = new ChatCompletionRequest("mock", List.of(new ChatMessage("user", "hello")), false);
        ProviderResponse result = orchestrator.complete(request);

        assertThat(result).isInstanceOf(ProviderResponse.Completion.class);
        assertThat(((ProviderResponse.Completion) result).response()).isEqualTo(expectedResponse);
    }

    @Test
    void propagatesAProviderErrorUnchanged() {
        var expectedError = new ProviderResponse.ProviderError("internal_error", "boom", 500, false);
        ProviderCaller stubCaller = request -> expectedError;
        var orchestrator = new ChatCompletionOrchestrator(stubCaller);

        var request = new ChatCompletionRequest("mock", List.of(new ChatMessage("user", "hello")), false);
        ProviderResponse result = orchestrator.complete(request);

        assertThat(result).isEqualTo(expectedError);
    }
}
