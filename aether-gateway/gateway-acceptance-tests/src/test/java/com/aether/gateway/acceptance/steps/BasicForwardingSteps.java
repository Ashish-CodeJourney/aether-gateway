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

public class BasicForwardingSteps {

    private final GatewayClient client = new GatewayClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private HttpResponse<String> lastResponse;

    @Given("the gateway is running with the mock provider configured")
    public void theGatewayIsRunningWithTheMockProviderConfigured() {
        assertThat(AcceptanceEnvironment.gatewayBaseUrl()).isNotBlank();
    }

    @When("a client sends a chat completion request for model {string}")
    public void aClientSendsAChatCompletionRequestForModel(String model) throws Exception {
        String body = """
                {"model": "%s", "messages": [{"role": "user", "content": "hello"}]}
                """.formatted(model);
        lastResponse = client.postJson(AcceptanceEnvironment.gatewayBaseUrl() + "/v1/chat/completions", body);
    }

    @Then("the response has an OpenAI-shaped choices array")
    public void theResponseHasAnOpenAiShapedChoicesArray() {
        JsonNode root = objectMapper.readTree(lastResponse.body());
        assertThat(root.has("choices")).isTrue();
        assertThat(root.get("choices").isArray()).isTrue();
        assertThat(root.get("choices")).hasSize(1);
        assertThat(root.get("choices").get(0).has("message")).isTrue();
    }

    @Then("the response status is {int}")
    public void theResponseStatusIs(int expectedStatus) {
        assertThat(lastResponse.statusCode()).isEqualTo(expectedStatus);
    }
}
