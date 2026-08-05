package com.aether.gateway.acceptance.steps;

import com.aether.gateway.acceptance.support.AcceptanceEnvironment;
import com.aether.gateway.acceptance.support.GatewayClient;
import io.cucumber.java.After;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F9.3, the end-to-end proof: the {@code @m9 @F9.3} scenario the plan
 * requirement (docs/design/requirements.md, F9.3; section
 * 6) specifies. Unit/integration coverage already exists
 * (ProviderBaseUrlValidatorTest, RoutingPolicyRepositoryTest) - this is
 * the missing outer layer, against the real spawned gateway process and
 * its real {@code /admin/routes/reload} endpoint.
 */
public class SsrfProtectionSteps {

    private final GatewayClient client = new GatewayClient();
    private String originalRoutingYaml;
    private HttpResponse<String> reloadResponse;

    @Given("an operator attempts to configure a provider base URL pointing at a private IP range")
    public void anOperatorAttemptsToConfigureAProviderBaseUrlPointingAtAPrivateIpRange() throws Exception {
        Path path = AcceptanceEnvironment.routingConfigPath();
        originalRoutingYaml = Files.readString(path);
        // Appends a real (non-mock) provider type pointing at a private
        // IP alongside the existing, working mock-primary/mock-fallback
        // config - proving a *mixed* reload (one good provider, one bad
        // one) is rejected as a whole, not partially applied.
        String withPrivateProvider = originalRoutingYaml.replaceFirst(
                "(?m)^providers:$",
                "providers:\n  evil-internal:\n    baseUrl: http://127.0.0.1:9\n    type: openai-compatible");
        Files.writeString(path, withPrivateProvider);
    }

    @When("the routing policy is reloaded with that configuration")
    public void theRoutingPolicyIsReloadedWithThatConfiguration() throws Exception {
        reloadResponse = client.postJson(
                AcceptanceEnvironment.gatewayBaseUrl() + "/admin/routes/reload", "", null,
                Map.of("X-Aether-Admin-Key", AcceptanceEnvironment.adminApiKey()));
    }

    @Then("the configuration is rejected")
    public void theConfigurationIsRejected() {
        assertThat(reloadResponse.statusCode()).isEqualTo(400);
    }

    @Then("a subsequent chat completion request through the {string} route still succeeds")
    public void aSubsequentChatCompletionRequestThroughTheRouteStillSucceeds(String model) throws Exception {
        String body = "{\"model\": \"" + model + "\", \"messages\": [{\"role\": \"user\", \"content\": \"still working?\"}]}";
        HttpResponse<String> response = client.postJson(AcceptanceEnvironment.gatewayBaseUrl() + "/v1/chat/completions", body);
        assertThat(response.statusCode()).isEqualTo(200);
    }

    @After
    public void restoreTheOriginalRoutingConfig() throws Exception {
        if (originalRoutingYaml != null) {
            Path path = AcceptanceEnvironment.routingConfigPath();
            Files.writeString(path, originalRoutingYaml);
            client.postJson(AcceptanceEnvironment.gatewayBaseUrl() + "/admin/routes/reload", "", null,
                    Map.of("X-Aether-Admin-Key", AcceptanceEnvironment.adminApiKey()));
        }
    }
}
