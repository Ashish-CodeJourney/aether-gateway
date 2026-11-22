package com.aether.gateway.acceptance.steps;

import com.aether.gateway.acceptance.support.AcceptanceEnvironment;
import com.aether.gateway.acceptance.support.GatewayClient;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

public class EmbeddingsSteps {

    private final GatewayClient client = new GatewayClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private HttpResponse<String> lastResponse;
    private HttpResponse<String> previousResponse;

    @Given("both mock providers are healthy")
    public void bothMockProvidersAreHealthy() throws Exception {
        client.postJson(AcceptanceEnvironment.mockPrimaryBaseUrl() + "/_mock/reset", "{}");
        client.postJson(AcceptanceEnvironment.mockFallbackBaseUrl() + "/_mock/reset", "{}");
    }

    @Given("the primary mock provider fails every request with a 503")
    public void thePrimaryMockProviderFailsEveryRequestWithA503() throws Exception {
        client.postJson(
                AcceptanceEnvironment.mockPrimaryBaseUrl() + "/_mock/config",
                "{\"fail_mode\": \"503\", \"fail_rate\": 1.0}");
    }

    @When("a client requests embeddings for {string} and {string} with model {string}")
    public void aClientRequestsEmbeddings(String first, String second, String model) throws Exception {
        String body = """
                {"model": "%s", "input": ["%s", "%s"]}
                """.formatted(model, first, second);
        previousResponse = lastResponse;
        lastResponse = client.postJson(AcceptanceEnvironment.gatewayBaseUrl() + "/v1/embeddings", body);
    }

    @When("the client requests embeddings for {string} and {string} with model {string} again")
    public void theClientRequestsEmbeddingsAgain(String first, String second, String model) throws Exception {
        aClientRequestsEmbeddings(first, second, model);
    }

    @When("a client requests embeddings for token ids with model {string}")
    public void aClientRequestsEmbeddingsForTokenIds(String model) throws Exception {
        String body = """
                {"model": "%s", "input": [1820, 25977]}
                """.formatted(model);
        lastResponse = client.postJson(AcceptanceEnvironment.gatewayBaseUrl() + "/v1/embeddings", body);
    }

    @Then("the embeddings response is successful")
    public void theEmbeddingsResponseIsSuccessful() {
        assertThat(lastResponse.statusCode()).as(lastResponse.body()).isEqualTo(200);
    }

    @Then("the embeddings response status is {int}")
    public void theEmbeddingsResponseStatusIs(int status) {
        assertThat(lastResponse.statusCode()).as(lastResponse.body()).isEqualTo(status);
    }

    @Then("the embeddings response is an OpenAI-shaped list of {int} embeddings")
    public void theEmbeddingsResponseIsAnOpenAiShapedList(int count) {
        JsonNode root = objectMapper.readTree(lastResponse.body());
        assertThat(root.get("object").asString()).isEqualTo("list");
        assertThat(root.get("data")).hasSize(count);
        for (int i = 0; i < count; i++) {
            JsonNode item = root.get("data").get(i);
            assertThat(item.get("object").asString()).isEqualTo("embedding");
            assertThat(item.get("index").asInt()).isEqualTo(i);
            assertThat(item.get("embedding").isArray()).isTrue();
            assertThat(item.get("embedding")).isNotEmpty();
        }
    }

    @Then("the embeddings response reports prompt token usage")
    public void theEmbeddingsResponseReportsPromptTokenUsage() {
        JsonNode usage = objectMapper.readTree(lastResponse.body()).get("usage");
        assertThat(usage.get("prompt_tokens").asInt()).isPositive();
        assertThat(usage.get("total_tokens").asInt()).isEqualTo(usage.get("prompt_tokens").asInt());
    }

    @Then("the embeddings response was served by {string}")
    public void theEmbeddingsResponseWasServedBy(String provider) {
        assertThat(lastResponse.headers().firstValue("X-Aether-Provider")).hasValue(provider);
    }

    @Then("the embeddings response carries a cost header")
    public void theEmbeddingsResponseCarriesACostHeader() {
        assertThat(lastResponse.headers().firstValue("X-Aether-Cost-USD"))
                .hasValueSatisfying(cost -> assertThat(Double.parseDouble(cost)).isNotNegative());
    }

    @Then("the embeddings response reports more than {int} attempt")
    public void theEmbeddingsResponseReportsMoreThanAttempts(int attempts) {
        assertThat(lastResponse.headers().firstValue("X-Aether-Attempts"))
                .hasValueSatisfying(value -> assertThat(Integer.parseInt(value)).isGreaterThan(attempts));
    }

    @Then("both embeddings responses carry identical vectors")
    public void bothEmbeddingsResponsesCarryIdenticalVectors() {
        assertThat(previousResponse).isNotNull();
        JsonNode first = objectMapper.readTree(previousResponse.body()).get("data");
        JsonNode second = objectMapper.readTree(lastResponse.body()).get("data");
        assertThat(second).isEqualTo(first);
    }

    @Then("the embeddings error mentions {string}")
    public void theEmbeddingsErrorMentions(String fragment) {
        JsonNode error = objectMapper.readTree(lastResponse.body()).get("error");
        assertThat(error.get("message").asString()).containsIgnoringCase(fragment);
    }
}
