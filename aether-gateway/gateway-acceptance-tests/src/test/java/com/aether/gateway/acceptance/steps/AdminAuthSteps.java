package com.aether.gateway.acceptance.steps;

import com.aether.gateway.acceptance.support.AcceptanceEnvironment;
import com.aether.gateway.acceptance.support.GatewayClient;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** F8.5: admin authentication is a real, separate boundary from gateway API keys. */
public class AdminAuthSteps {

    private final GatewayClient client = new GatewayClient();
    private String gatewayApiKey;
    private HttpResponse<String> lastResponse;

    @Given("a valid gateway API key with no admin privileges")
    public void aValidGatewayApiKeyWithNoAdminPrivileges() {
        gatewayApiKey = "gw-key-" + UUID.randomUUID();
        AcceptanceEnvironment.seedApiKey(gatewayApiKey, 100, 100, 1_000_000L);
    }

    @When("that key is used to call an admin endpoint")
    public void thatKeyIsUsedToCallAnAdminEndpoint() throws Exception {
        lastResponse = client.postJson(
                AcceptanceEnvironment.gatewayBaseUrl() + "/admin/routes/reload", "", gatewayApiKey);
    }

    @When("the correct admin key is used to call an admin endpoint")
    public void theCorrectAdminKeyIsUsedToCallAnAdminEndpoint() throws Exception {
        lastResponse = client.postJson(
                AcceptanceEnvironment.gatewayBaseUrl() + "/admin/routes/reload", "", null,
                Map.of("X-Aether-Admin-Key", AcceptanceEnvironment.adminApiKey()));
    }

    @Then("the response status is 401 or 403")
    public void theResponseStatusIs401Or403() {
        assertThat(lastResponse.statusCode()).isIn(401, 403);
    }

    @Then("the admin call succeeds")
    public void theAdminCallSucceeds() {
        assertThat(lastResponse.statusCode()).isEqualTo(200);
    }
}
