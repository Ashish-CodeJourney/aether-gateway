package com.aether.gateway.acceptance.steps;

import com.aether.gateway.acceptance.support.AcceptanceEnvironment;
import com.aether.gateway.acceptance.support.GatewayClient;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

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

    private HttpResponse<String> lastResponse;

    private String requestBody(String prompt) {
        return "{\"model\": \"mock\", \"messages\": [{\"role\": \"user\", \"content\": \"" + escape(prompt) + "\"}]}";
    }

    private String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Given("a prior request was made and cached for prompt {string}")
    public void aPriorRequestWasMadeAndCachedForPrompt(String prompt) throws Exception {
        HttpResponse<String> response = client.postJson(
                AcceptanceEnvironment.gatewayBaseUrl() + "/v1/chat/completions", requestBody(prompt), null, null);
        assertThat(response.statusCode()).as("seeding the cache requires the seed request itself to succeed").isEqualTo(200);
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
        return apiKeysByAlias.computeIfAbsent(alias, a -> {
            String rawKey = "aeth_acceptance_" + a + "_" + UUID.randomUUID();
            AcceptanceEnvironment.seedApiKey(rawKey, null, null, null);
            return rawKey;
        });
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
