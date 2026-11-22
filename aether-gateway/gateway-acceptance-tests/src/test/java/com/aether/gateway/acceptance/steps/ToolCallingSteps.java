package com.aether.gateway.acceptance.steps;

import com.aether.gateway.acceptance.support.AcceptanceEnvironment;
import com.aether.gateway.acceptance.support.GatewayClient;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

public class ToolCallingSteps {

    private final GatewayClient client = new GatewayClient();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    private HttpResponse<String> lastResponse;
    private List<HttpResponse<String>> repeatedResponses;
    private List<JsonNode> streamedChunks;
    private boolean streamEndedWithDone;

    private static String toolDefinition(String name) {
        return """
                {"type":"function","function":{"name":"%s","description":"Look something up","parameters":{"type":"object","properties":{"location":{"type":"string"}},"required":["location"]}}}
                """.formatted(name).strip();
    }

    private static String toolRequest(String toolName, String prompt, boolean stream) {
        return """
                {"model":"mock","stream":%s,"tool_choice":"auto","tools":[%s],
                 "messages":[{"role":"user","content":"%s"}]}
                """.formatted(stream, toolDefinition(toolName), prompt);
    }

    private String chatUrl() {
        return AcceptanceEnvironment.gatewayBaseUrl() + "/v1/chat/completions";
    }

    private JsonNode firstChoice() {
        return objectMapper.readTree(lastResponse.body()).get("choices").get(0);
    }

    @When("a client offers the {string} tool in a chat completion request")
    public void aClientOffersTheToolInAChatCompletionRequest(String toolName) throws Exception {
        lastResponse = client.postJson(chatUrl(), toolRequest(toolName, "What is the weather like?", false));
    }

    @When("a client returns the result {string} for tool call {string} of {string}")
    public void aClientReturnsTheResultForToolCallOf(String result, String toolCallId, String toolName) throws Exception {
        String body = """
                {"model":"mock","tools":[%s],
                 "messages":[
                   {"role":"user","content":"What is the weather like?"},
                   {"role":"assistant","content":null,"tool_calls":[{"id":"%s","type":"function","function":{"name":"%s","arguments":"{\\"location\\":\\"mock-city\\"}"}}]},
                   {"role":"tool","tool_call_id":"%s","content":"%s"}]}
                """.formatted(toolDefinition(toolName), toolCallId, toolName, toolCallId, result);
        lastResponse = client.postJson(chatUrl(), body);
    }

    @When("a client offers the {string} tool in a streaming chat completion request")
    public void aClientOffersTheToolInAStreamingChatCompletionRequest(String toolName) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(chatUrl()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(toolRequest(toolName, "What is the weather like?", true)))
                .build();
        HttpResponse<java.util.stream.Stream<String>> response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines());
        assertThat(response.statusCode()).isEqualTo(200);
        List<String> dataLines;
        try (var lines = response.body()) {
            dataLines = lines.filter(l -> l.startsWith("data:"))
                    .map(l -> l.substring("data:".length()).strip())
                    .collect(Collectors.toList());
        }
        streamEndedWithDone = !dataLines.isEmpty() && "[DONE]".equals(dataLines.getLast());
        streamedChunks = dataLines.stream()
                .filter(l -> !"[DONE]".equals(l))
                .map(objectMapper::readTree)
                .toList();
    }

    @When("a client offers the {string} tool in the same chat completion request twice")
    public void aClientOffersTheToolInTheSameChatCompletionRequestTwice(String toolName) throws Exception {
        String body = toolRequest(toolName, "Where is my order " + UUID.randomUUID() + "?", false);
        repeatedResponses = List.of(client.postJson(chatUrl(), body), client.postJson(chatUrl(), body));
    }

    @Then("the response status is {int} for the tool call request")
    public void theResponseStatusIsForTheToolCallRequest(int status) {
        assertThat(lastResponse.statusCode()).as(lastResponse.body()).isEqualTo(status);
    }

    @Then("the response finishes with reason {string}")
    public void theResponseFinishesWithReason(String reason) {
        assertThat(firstChoice().get("finish_reason").asString()).isEqualTo(reason);
    }

    @Then("the response calls {string} with arguments {}")
    public void theResponseCallsWithArguments(String toolName, String argumentsJson) {
        JsonNode call = firstChoice().get("message").get("tool_calls").get(0);
        assertThat(call.get("id").asString()).isNotBlank();
        assertThat(call.get("type").asString()).isEqualTo("function");
        assertThat(call.get("function").get("name").asString()).isEqualTo(toolName);
        assertThat(objectMapper.readTree(call.get("function").get("arguments").asString()))
                .isEqualTo(objectMapper.readTree(argumentsJson));
    }

    @Then("the tool-calling assistant message has null content")
    public void theToolCallingAssistantMessageHasNullContent() {
        JsonNode message = firstChoice().get("message");
        assertThat(message.has("content")).isTrue();
        assertThat(message.get("content").isNull()).isTrue();
    }

    @Then("the assistant replies {string}")
    public void theAssistantReplies(String expected) {
        assertThat(firstChoice().get("message").get("content").asString()).isEqualTo(expected);
    }

    @Then("the first tool call fragment carries an id and the function name {string}")
    public void theFirstToolCallFragmentCarriesAnIdAndTheFunctionName(String toolName) {
        JsonNode first = toolCallFragments().getFirst();
        assertThat(first.get("index").asInt()).isZero();
        assertThat(first.get("id").asString()).isNotBlank();
        assertThat(first.get("function").get("name").asString()).isEqualTo(toolName);
    }

    @Then("the streamed tool call arguments reassemble to {}")
    public void theStreamedToolCallArgumentsReassembleTo(String argumentsJson) {
        List<JsonNode> fragments = toolCallFragments();
        assertThat(fragments).hasSizeGreaterThan(2);
        String arguments = fragments.stream()
                .map(f -> f.path("function").path("arguments").asString(""))
                .collect(Collectors.joining());
        assertThat(objectMapper.readTree(arguments)).isEqualTo(objectMapper.readTree(argumentsJson));
    }

    @Then("the stream finishes with reason {string}")
    public void theStreamFinishesWithReason(String reason) {
        JsonNode lastChoice = streamedChunks.getLast().get("choices").get(0);
        assertThat(lastChoice.get("finish_reason").asString()).isEqualTo(reason);
    }

    @Then("the tool call stream ends with a DONE marker")
    public void theToolCallStreamEndsWithADoneMarker() {
        assertThat(streamEndedWithDone).isTrue();
    }

    @Then("both responses bypassed the cache")
    public void bothResponsesBypassedTheCache() {
        assertThat(repeatedResponses).allSatisfy(response -> {
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("X-Aether-Cache")).hasValue("BYPASS");
        });
    }

    @Then("both responses call {string}")
    public void bothResponsesCall(String toolName) {
        assertThat(repeatedResponses).allSatisfy(response -> {
            JsonNode call = objectMapper.readTree(response.body()).get("choices").get(0).get("message").get("tool_calls").get(0);
            assertThat(call.get("function").get("name").asString()).isEqualTo(toolName);
        });
    }

    private List<JsonNode> toolCallFragments() {
        return streamedChunks.stream()
                .map(chunk -> chunk.get("choices").get(0).path("delta").path("tool_calls"))
                .filter(JsonNode::isArray)
                .flatMap(array -> array.valueStream())
                .toList();
    }
}
