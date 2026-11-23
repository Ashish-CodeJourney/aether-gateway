package com.aether.gateway.acceptance.steps;

import com.aether.gateway.acceptance.support.AcceptanceEnvironment;
import com.aether.gateway.acceptance.support.GatewayClient;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR-010: what is specific to a gateway started with the lite profile. */
public class LiteModeSteps {

    private final GatewayClient client = new GatewayClient();
    private final JsonMapper json = JsonMapper.builder().build();
    private HttpResponse<String> lastResponse;

    @When("an operator reads the gateway's info endpoint")
    public void anOperatorReadsTheGatewaysInfoEndpoint() throws Exception {
        lastResponse = client.get(AcceptanceEnvironment.gatewayBaseUrl() + "/actuator/info");
    }

    @Then("the gateway reports that it is running in {string} mode")
    public void theGatewayReportsThatItIsRunningInMode(String mode) {
        assertThat(lastResponse.statusCode()).isEqualTo(200);
        assertThat(json.readTree(lastResponse.body()).get("aether").get("mode").asString()).isEqualTo(mode);
    }

    @When("a client sends {string} to the {string} route")
    public void aClientSendsToTheRoute(String prompt, String route) throws Exception {
        String body = """
                {"model": "%s", "messages": [{"role": "user", "content": "%s"}]}
                """.formatted(route, prompt);
        lastResponse = client.postJson(AcceptanceEnvironment.gatewayBaseUrl() + "/v1/chat/completions", body);
        assertThat(lastResponse.statusCode()).as("body: %s", lastResponse.body()).isEqualTo(200);
    }

    @Then("the reply content is {string}")
    public void theReplyContentIs(String expected) {
        JsonNode message = json.readTree(lastResponse.body()).get("choices").get(0).get("message");
        assertThat(message.get("content").asString()).isEqualTo(expected);
    }

    @Then("the reply was served with cache outcome {string}")
    public void theReplyWasServedWithCacheOutcome(String outcome) {
        assertThat(lastResponse.headers().firstValue("X-Aether-Cache")).hasValue(outcome);
    }

    @Then("the usage report grouped by model includes {string}")
    public void theUsageReportGroupedByModelIncludes(String model) throws Exception {
        Instant now = Instant.now();
        String url = AcceptanceEnvironment.gatewayBaseUrl() + "/admin/usage?groupBy=model&from=%s&to=%s"
                .formatted(now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(1)));
        Map<String, String> admin = Map.of("X-Aether-Admin-Key", AcceptanceEnvironment.adminApiKey());

        // The request log is written off the response path (F6.2), so
        // the entry can land just after the reply has been read.
        long deadline = System.currentTimeMillis() + 5000;
        List<String> models = List.of();
        while (!models.contains(model) && System.currentTimeMillis() < deadline) {
            HttpResponse<String> usage = client.get(url, admin);
            assertThat(usage.statusCode()).as("body: %s", usage.body()).isEqualTo(200);
            models = json.readTree(usage.body()).valueStream().map(row -> row.get("group_value").asString()).toList();
            Thread.sleep(100);
        }
        assertThat(models).contains(model);
    }
}
