package com.aether.gateway.acceptance.steps;

import com.aether.gateway.acceptance.support.AcceptanceEnvironment;
import com.aether.gateway.acceptance.support.GatewayClient;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 08 (M5) / F6.2, F6.3, F6.4: pins the data guarantees the
 * Grafana dashboard depends on at the API/data layer - per
 * TESTING-STRATEGY.md, a dashboard is a visual artifact Cucumber
 * shouldn't assert against directly, so these scenarios instead prove
 * the request_log rows the dashboard's Prometheus metrics are derived
 * from are actually correct, against the real gateway process and real
 * Postgres.
 */
public class ObservabilitySteps {

    private static final BigDecimal MOCK_INPUT_PRICE_PER_MILLION = new BigDecimal("0.50");
    private static final BigDecimal MOCK_OUTPUT_PRICE_PER_MILLION = new BigDecimal("1.50");

    private final GatewayClient client = new GatewayClient();
    private Map<String, Object> lastLogEntry;

    @Given("the cost model prices the {string} model's input tokens at {string} and output tokens at {string} per million")
    public void theCostModelPricesTheModel(String model, String inputPrice, String outputPrice) {
        // Documents/pins the known static configuration
        // (aether-gateway/cost-model.yaml) that the expected-cost
        // calculation below is computed against; not a live
        // configuration step, since the acceptance environment's
        // gateway process already loads this file at startup.
        assertThat(model).isEqualTo("mock");
        assertThat(new BigDecimal(inputPrice)).isEqualByComparingTo(MOCK_INPUT_PRICE_PER_MILLION);
        assertThat(new BigDecimal(outputPrice)).isEqualByComparingTo(MOCK_OUTPUT_PRICE_PER_MILLION);
    }

    @When("a client sends {string} that is not served from cache")
    public void aClientSendsThatIsNotServedFromCache(String prompt) throws Exception {
        String body = "{\"model\": \"mock\", \"messages\": [{\"role\": \"user\", \"content\": \"" + prompt + "\"}]}";
        var response = client.postJson(
                AcceptanceEnvironment.gatewayBaseUrl() + "/v1/chat/completions", body, null,
                Map.of("X-Aether-No-Cache", "true"));
        assertThat(response.statusCode()).isEqualTo(200);
    }

    @Then("a request log entry exists for this request")
    public void aRequestLogEntryExistsForThisRequest() {
        lastLogEntry = AcceptanceEnvironment.mostRecentRequestLogEntry();
        assertThat(lastLogEntry).isNotNull();
    }

    @Then("that entry's cache outcome is {string} or {string}")
    public void thatEntrysCacheOutcomeIsOr(String optionA, String optionB) {
        Object cacheOutcome = lastLogEntry.get("cache_outcome");
        assertThat(cacheOutcome).isIn(optionA, optionB);
    }

    @Then("that entry's {string} value is greater than zero")
    public void thatEntrysValueIsGreaterThanZero(String columnName) {
        Object value = lastLogEntry.get(columnName);
        assertThat(value).as("column %s in %s", columnName, lastLogEntry).isNotNull();
        assertThat(((Number) value).doubleValue()).isGreaterThan(0.0);
    }

    @Then("that entry's {string} matches the expected value computed from the configured rates and reported token usage")
    public void thatEntrysCostMatchesTheExpectedValue(String columnName) {
        int inputTokens = ((Number) lastLogEntry.get("input_tokens")).intValue();
        int outputTokens = ((Number) lastLogEntry.get("output_tokens")).intValue();
        BigDecimal expected = MOCK_INPUT_PRICE_PER_MILLION.multiply(BigDecimal.valueOf(inputTokens))
                .add(MOCK_OUTPUT_PRICE_PER_MILLION.multiply(BigDecimal.valueOf(outputTokens)))
                .divide(BigDecimal.valueOf(1_000_000));

        BigDecimal actual = (BigDecimal) lastLogEntry.get(columnName);
        assertThat(actual.doubleValue())
                .as("cost_usd for %d input / %d output tokens", inputTokens, outputTokens)
                .isCloseTo(expected.doubleValue(), org.assertj.core.data.Offset.offset(0.0000001));
    }
}
