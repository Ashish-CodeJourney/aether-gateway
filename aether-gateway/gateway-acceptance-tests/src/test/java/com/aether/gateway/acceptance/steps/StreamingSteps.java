package com.aether.gateway.acceptance.steps;

import com.aether.gateway.acceptance.support.AcceptanceEnvironment;
import com.aether.gateway.acceptance.support.GatewayClient;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

public class StreamingSteps {

    private final GatewayClient client = new GatewayClient();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    private long cancelledAtNanos;
    private List<String> receivedLines;
    private HttpResponse<String> lastModelsResponse;

    @Before
    public void resetMockProvider() throws Exception {
        client.postJson(AcceptanceEnvironment.mockProviderBaseUrl() + "/_mock/reset", "{}");
    }

    @Given("the mock provider is configured with a {int} millisecond delay between stream chunks")
    public void theMockProviderIsConfiguredWithADelayBetweenStreamChunks(int delayMs) throws Exception {
        String body = "{\"stream_delay_ms\": %d}".formatted(delayMs);
        client.postJson(AcceptanceEnvironment.mockProviderBaseUrl() + "/_mock/config", body);
    }

    @Given("the mock provider has no configured delay")
    public void theMockProviderHasNoConfiguredDelay() throws Exception {
        client.postJson(AcceptanceEnvironment.mockProviderBaseUrl() + "/_mock/reset", "{}");
    }

    @When("a client sends a streaming chat completion request and aborts after receiving {int} chunks")
    public void aClientSendsAStreamingChatCompletionRequestAndAbortsAfterReceivingChunks(int chunkCount) {
        readSseLines(chunkCount);
        cancelledAtNanos = System.nanoTime();
    }

    @When("a client sends a streaming chat completion request to completion")
    public void aClientSendsAStreamingChatCompletionRequestToCompletion() {
        receivedLines = readSseLines(Integer.MAX_VALUE);
    }

    @Then("the upstream connection to the mock provider closes within {int} milliseconds")
    public void theUpstreamConnectionToTheMockProviderClosesWithinMilliseconds(int maxMillis) throws Exception {
        long deadline = cancelledAtNanos + (long) maxMillis * 1_000_000L;
        int active;
        do {
            active = currentActiveStreams();
            if (active == 0) {
                break;
            }
            Thread.sleep(5);
        } while (System.nanoTime() < deadline);

        long elapsedMs = (System.nanoTime() - cancelledAtNanos) / 1_000_000L;
        assertThat(active)
                .as("active streams on the mock provider %dms after client abort", elapsedMs)
                .isZero();
        assertThat(elapsedMs).isLessThan(maxMillis);
    }

    @Then("the client receives at least one non-empty content chunk")
    public void theClientReceivesAtLeastOneNonEmptyContentChunk() {
        boolean hasContent = receivedLines.stream()
                .filter(l -> l.startsWith("data:") && !l.contains("[DONE]"))
                .anyMatch(l -> l.contains("\"content\":\"") && !l.contains("\"content\":\"\""));
        assertThat(hasContent).as("received at least one chunk with non-empty delta content").isTrue();
    }

    @Then("the stream ends with a DONE marker")
    public void theStreamEndsWithADoneMarker() {
        assertThat(receivedLines).anyMatch(l -> l.contains("[DONE]"));
    }

    @When("a client requests the list of available models")
    public void aClientRequestsTheListOfAvailableModels() throws Exception {
        lastModelsResponse = client.get(AcceptanceEnvironment.gatewayBaseUrl() + "/v1/models");
    }

    @Then("the response includes the mock provider's declared models")
    public void theResponseIncludesTheMockProvidersDeclaredModels() {
        JsonNode root = objectMapper.readTree(lastModelsResponse.body());
        assertThat(root.get("data")).isNotEmpty();
        List<String> ids = new CopyOnWriteArrayList<>();
        root.get("data").forEach(n -> ids.add(n.get("id").asString()));
        assertThat(ids).contains("mock");
    }

    private List<String> readSseLines(int chunkLimit) {
        String body = """
                {"model": "mock", "messages": [{"role": "user", "content": "hello"}], "stream": true}
                """;
        HttpRequest request = HttpRequest.newBuilder(URI.create(AcceptanceEnvironment.gatewayBaseUrl() + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            HttpResponse<java.util.stream.Stream<String>> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofLines());
            try (var lines = response.body()) {
                return lines
                        .filter(l -> !l.isBlank())
                        .limit(chunkLimit)
                        .collect(Collectors.toList());
            }
        } catch (Exception e) {
            throw new IllegalStateException("Streaming request failed", e);
        }
    }

    private int currentActiveStreams() {
        try {
            HttpResponse<String> response = client.get(AcceptanceEnvironment.mockProviderBaseUrl() + "/_mock/active-streams");
            JsonNode node = objectMapper.readTree(response.body());
            return node.get("active").asInt();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to query active streams", e);
        }
    }
}
