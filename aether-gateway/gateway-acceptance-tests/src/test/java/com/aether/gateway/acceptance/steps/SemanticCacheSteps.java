package com.aether.gateway.acceptance.steps;

import com.aether.gateway.acceptance.support.AcceptanceEnvironment;
import com.aether.gateway.acceptance.support.GatewayClient;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 07 (M4) / F4.2, F4.4, F4.5: the direct behavioral proof of the
 * correctness tradeoff the PRD is most explicit about (the "what is
 * 2+2 vs what is 2+3" failure mode), run against the real gateway
 * process, real Postgres+pgvector, real Redis, and the real embedding
 * model - never a fake.
 */
public class SemanticCacheSteps {

    private final GatewayClient client = new GatewayClient();
    private final Map<String, String> apiKeysByAlias = new HashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private HttpResponse<String> lastResponse;
    private String route = "mock";

    private String requestBody(String prompt) {
        return "{\"model\": \"" + route + "\", \"messages\": [{\"role\": \"user\", \"content\": \"" + escape(prompt) + "\"}]}";
    }

    private String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Given("cache requests target the {string} route")
    public void cacheRequestsTargetTheRoute(String alias) {
        route = alias;
    }

    @Given("a prior request was made and cached for prompt {string}")
    public void aPriorRequestWasMadeAndCachedForPrompt(String prompt) throws Exception {
        // The strictest threshold, so the seed is stored as its own entry
        // rather than served from some looser match left by an earlier
        // scenario in the shared anonymous namespace.
        HttpResponse<String> response = client.postJson(
                AcceptanceEnvironment.gatewayBaseUrl() + "/v1/chat/completions",
                requestBody(prompt), null, Map.of("X-Aether-Cache-Threshold", "1.0"));
        assertThat(response.statusCode()).as("seeding the cache requires the seed request itself to succeed").isEqualTo(200);
    }

    @When("a client sends {string} with no cache threshold header")
    public void aClientSendsWithNoCacheThresholdHeader(String prompt) throws Exception {
        lastResponse = client.postJson(
                AcceptanceEnvironment.gatewayBaseUrl() + "/v1/chat/completions", requestBody(prompt), null, null);
    }

    @When("a client sends {string} with cache threshold {string}")
    public void aClientSendsWithCacheThreshold(String prompt, String threshold) throws Exception {
        lastResponse = client.postJson(
                AcceptanceEnvironment.gatewayBaseUrl() + "/v1/chat/completions",
                requestBody(prompt), null, Map.of("X-Aether-Cache-Threshold", threshold));
    }

    @Given("API key {string} has a cached response for {string}")
    public void apiKeyHasACachedResponseFor(String alias, String prompt) throws Exception {
        String rawKey = apiKeyFor(alias);
        HttpResponse<String> response = client.postJson(
                AcceptanceEnvironment.gatewayBaseUrl() + "/v1/chat/completions", requestBody(prompt), rawKey, null);
        assertThat(response.statusCode()).isEqualTo(200);
    }

    @When("API key {string} sends the identical prompt {string}")
    public void apiKeySendsTheIdenticalPrompt(String alias, String prompt) throws Exception {
        String rawKey = apiKeyFor(alias);
        lastResponse = client.postJson(
                AcceptanceEnvironment.gatewayBaseUrl() + "/v1/chat/completions", requestBody(prompt), rawKey, null);
    }

    private String apiKeyFor(String alias) {
        return apiKeysByAlias.computeIfAbsent(alias, a -> AcceptanceEnvironment.createApiKey(null, null, null));
    }

    @Then("the request is rejected with status {int} and error {string}")
    public void theRequestIsRejectedWithStatusAndError(int status, String errorTitle) {
        assertThat(lastResponse.statusCode()).as("body: %s", lastResponse.body()).isEqualTo(status);
        JsonNode body = objectMapper.readTree(lastResponse.body());
        assertThat(body.get("title").asText()).isEqualTo(errorTitle);
        assertThat(body.get("detail").asText()).contains("X-Aether-Cache-Threshold");
    }

    @Then("the response header {string} is {string}")
    public void theResponseHeaderIs(String headerName, String expectedValue) {
        assertThat(lastResponse.headers().firstValue(headerName))
                .as("response headers: %s, body: %s", lastResponse.headers().map(), lastResponse.body())
                .hasValue(expectedValue);
    }

    @Then("the response header {string} is not {string}")
    public void theResponseHeaderIsNot(String headerName, String forbiddenValue) {
        assertThat(lastResponse.headers().firstValue(headerName))
                .as("response headers: %s, body: %s", lastResponse.headers().map(), lastResponse.body())
                .isNotEqualTo(java.util.Optional.of(forbiddenValue));
    }

    @Then("the response header {string} is at least {string}")
    public void theResponseHeaderIsAtLeast(String headerName, String minimumValue) {
        String actual = lastResponse.headers().firstValue(headerName)
                .orElseThrow(() -> new AssertionError("Missing response header " + headerName + "; body: " + lastResponse.body()));
        assertThat(Double.parseDouble(actual)).isGreaterThanOrEqualTo(Double.parseDouble(minimumValue));
    }
}
