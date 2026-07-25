package com.aether.gateway.acceptance.steps;

import com.aether.gateway.acceptance.support.AcceptanceEnvironment;
import com.aether.gateway.acceptance.support.GatewayClient;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import java.net.http.HttpResponse;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** F7.1-F7.4: the exit criterion, driven purely through the admin API's HTTP surface. */
public class PromptRegistrySteps {

    private static final Map<String, String> ADMIN_HEADERS = Map.of("X-Aether-Admin-Key", AcceptanceEnvironment.adminApiKey());

    private final GatewayClient client = new GatewayClient();
    private String promptName;
    private HttpResponse<String> lastCompletionResponse;

    @Given("prompt {string} has version 1 and version 2")
    public void promptHasVersionOneAndVersionTwo(String name) throws Exception {
        promptName = name;
        client.postJson(AcceptanceEnvironment.gatewayBaseUrl() + "/admin/prompts", "{\"name\": \"" + promptName + "\"}", null, ADMIN_HEADERS);
        client.postJson(AcceptanceEnvironment.gatewayBaseUrl() + "/admin/prompts/" + promptName + "/versions",
                """
                {"template": [{"role": "user", "content": "Version one template, static text."}], "variables": []}
                """, null, ADMIN_HEADERS);
        client.postJson(AcceptanceEnvironment.gatewayBaseUrl() + "/admin/prompts/" + promptName + "/versions",
                """
                {"template": [{"role": "user", "content": "Version two template, static text."}], "variables": []}
                """, null, ADMIN_HEADERS);
    }

    @And("alias {string} points at version 2")
    public void aliasPointsAtVersionTwo(String alias) throws Exception {
        client.putJson(AcceptanceEnvironment.gatewayBaseUrl() + "/admin/prompts/" + promptName + "/aliases/" + alias, "{\"version\": 2}", ADMIN_HEADERS);
    }

    @When("a client sends a request referencing {string}")
    public void aClientSendsARequestReferencingPrompt(String reference) throws Exception {
        lastCompletionResponse = client.postJson(
                AcceptanceEnvironment.gatewayBaseUrl() + "/v1/chat/completions",
                "{\"model\": \"mock\", \"stream\": false}",
                null,
                Map.of("X-Aether-Prompt", reference, "X-Aether-No-Cache", "true"));
    }

    @Then("the response reflects version {int}'s template")
    public void theResponseReflectsVersionsTemplate(int version) {
        assertThat(lastCompletionResponse.statusCode()).isEqualTo(200);
        assertThat(lastCompletionResponse.headers().firstValue("X-Aether-Prompt-Version")).contains(String.valueOf(version));
    }

    @When("the {string} alias is repointed to version {int} via the admin API")
    public void theAliasIsRepointedToVersionViaTheAdminApi(String alias, int version) throws Exception {
        client.putJson(AcceptanceEnvironment.gatewayBaseUrl() + "/admin/prompts/" + promptName + "/aliases/" + alias,
                "{\"version\": " + version + "}", ADMIN_HEADERS);
    }
}
