package com.aether.gateway.acceptance.steps;

import com.aether.gateway.acceptance.support.AcceptanceEnvironment;
import com.aether.gateway.acceptance.support.GatewayClient;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

public class RoutingResilienceSteps {

    private final GatewayClient client = new GatewayClient();

    private HttpResponse<String> lastResponse;
    private long elapsedMillis;

    @Before
    public void resetMockProvidersAndWarmUpConnections() throws Exception {
        client.postJson(AcceptanceEnvironment.mockPrimaryBaseUrl() + "/_mock/reset", "{}");
        client.postJson(AcceptanceEnvironment.mockFallbackBaseUrl() + "/_mock/reset", "{}");
        // Warm up the gateway's outbound connection pool to the mock
        // providers before any timed assertion: the very first real
        // request from a freshly-started JVM measurably includes
        // connection-pool/class-loading cold start (observed ~1.7s
        // during manual M2 verification, vs 10-100ms once warm), which
        // would make the 500ms exit-criterion assertion flaky for
        // reasons unrelated to the actual failover behaviour being
        // tested.
        String warmupBody = """
                {"model": "mock", "messages": [{"role": "user", "content": "warmup"}]}
                """;
        client.postJson(AcceptanceEnvironment.gatewayBaseUrl() + "/v1/chat/completions", warmupBody);
    }

    @Given("the mock provider is configured to fail every request with a 503")
    public void theMockProviderIsConfiguredToFailEveryRequestWithA503() throws Exception {
        client.postJson(
                AcceptanceEnvironment.mockPrimaryBaseUrl() + "/_mock/config",
                "{\"fail_mode\": \"503\", \"fail_rate\": 1.0}");
    }

    @When("a client sends a chat completion request through the gateway")
    public void aClientSendsAChatCompletionRequestThroughTheGateway() throws Exception {
        String body = """
                {"model": "mock", "messages": [{"role": "user", "content": "hello"}]}
                """;
        long start = System.nanoTime();
        lastResponse = client.postJson(AcceptanceEnvironment.gatewayBaseUrl() + "/v1/chat/completions", body);
        elapsedMillis = (System.nanoTime() - start) / 1_000_000L;
    }

    @When("the routing policy is reloaded")
    public void theRoutingPolicyIsReloaded() throws Exception {
        HttpResponse<String> response = client.postJson(AcceptanceEnvironment.gatewayBaseUrl() + "/admin/routes/reload", "");
        assertThat(response.statusCode()).isEqualTo(200);
    }

    @Then("the response is successful")
    public void theResponseIsSuccessful() {
        assertThat(lastResponse.statusCode()).isEqualTo(200);
    }

    @Then("the response was returned within {int} milliseconds")
    public void theResponseWasReturnedWithinMilliseconds(int maxMillis) {
        assertThat(elapsedMillis)
                .as("gateway response time (%dms) for the M2 failover exit criterion", elapsedMillis)
                .isLessThan(maxMillis);
    }
}
