package com.aether.gateway.acceptance.steps;

import com.aether.gateway.acceptance.support.AcceptanceEnvironment;
import com.aether.gateway.acceptance.support.GatewayClient;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 06 (M3) / F5.5 / AC8: the direct behavioral form of this
 * milestone's exit criterion, run against the real gateway process, real
 * Redis, and real Postgres (see AcceptanceEnvironment), never a fake.
 *
 * <p>The gateway process is started with
 * {@code AETHER_QUOTA_ASSUMED_MAX_OUTPUT_TOKENS=12} (see
 * AcceptanceEnvironment#start), matching mock-provider's default
 * completion-token count exactly, so with the fixed short message body
 * used below every request has a known, constant *estimated* cost that
 * also equals its *actual* cost once the response comes back — no drift
 * from reconciliation racing other in-flight reservations. That equality
 * is what lets the "budget allowing exactly N requests" step compute an
 * exact budget and get a deterministic admit/reject split.
 */
public class QuotaSteps {

    // "hi": 1 prompt token (length 2 / 4, floored, minimum 1) + mock-provider's
    // default completion token count of 12 (see AcceptanceEnvironment#start).
    private static final int TOKENS_PER_REQUEST = 13;
    private static final String REQUEST_BODY = """
            {"model": "mock", "messages": [{"role": "user", "content": "hi"}]}
            """;

    private final GatewayClient client = new GatewayClient();

    private String apiKey;
    private final List<HttpResponse<String>> responses = new CopyOnWriteArrayList<>();

    @Given("an API key with a monthly token budget allowing exactly {int} requests of the test workload")
    public void anApiKeyWithAMonthlyTokenBudgetAllowingExactlyRequestsOfTheTestWorkload(int requestCount) {
        apiKey = "aeth_acceptance_" + UUID.randomUUID();
        long budget = (long) requestCount * TOKENS_PER_REQUEST;
        AcceptanceEnvironment.seedApiKey(apiKey, null, null, budget);
    }

    @Given("an API key with an RPS limit of {int}")
    public void anApiKeyWithAnRpsLimitOf(int rpsLimit) {
        apiKey = "aeth_acceptance_" + UUID.randomUUID();
        AcceptanceEnvironment.seedApiKey(apiKey, rpsLimit, null, null);
    }

    @When("{int} concurrent chat completion requests are sent using that key")
    public void concurrentChatCompletionRequestsAreSentUsingThatKey(int requestCount) throws InterruptedException {
        responses.clear();
        ExecutorService executor = Executors.newFixedThreadPool(32);
        CountDownLatch readyLatch = new CountDownLatch(requestCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(requestCount);

        for (int i = 0; i < requestCount; i++) {
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    responses.add(client.postJson(AcceptanceEnvironment.gatewayBaseUrl() + "/v1/chat/completions",
                            REQUEST_BODY, apiKey));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await(10, TimeUnit.SECONDS);
        startLatch.countDown();
        boolean completed = doneLatch.await(60, TimeUnit.SECONDS);
        executor.shutdown();
        assertThat(completed).as("all %d concurrent requests completed within the timeout", requestCount).isTrue();
    }

    @When("{int} requests are sent within {int} second(s) using that key")
    public void requestsAreSentWithinSecondsUsingThatKey(int requestCount, int withinSeconds) throws Exception {
        responses.clear();
        for (int i = 0; i < requestCount; i++) {
            responses.add(client.postJson(AcceptanceEnvironment.gatewayBaseUrl() + "/v1/chat/completions",
                    REQUEST_BODY, apiKey));
        }
    }

    @Then("exactly {int} requests succeed")
    public void exactlyRequestsSucceed(int expectedSuccessCount) {
        long succeeded = responses.stream().filter(r -> r.statusCode() == 200).count();
        assertThat(succeeded)
                .as("exactly %d of %d requests must succeed, no over-issue, no under-issue", expectedSuccessCount, responses.size())
                .isEqualTo(expectedSuccessCount);
    }

    @Then("exactly {int} requests are rejected with status {int}")
    public void exactlyRequestsAreRejectedWithStatus(int expectedRejectedCount, int status) {
        long rejected = responses.stream().filter(r -> r.statusCode() == status).count();
        assertThat(rejected).isEqualTo(expectedRejectedCount);
    }

    @Then("at least one request is rejected with status {int}")
    public void atLeastOneRequestIsRejectedWithStatus(int status) {
        assertThat(responses).anyMatch(r -> r.statusCode() == status);
    }

    @Then("each rejected response includes an {string} header of {string}")
    public void eachRejectedResponseIncludesAHeaderOf(String headerName, String expectedValue) {
        List<HttpResponse<String>> rejected = responses.stream().filter(r -> r.statusCode() == 429).toList();
        assertThat(rejected).isNotEmpty();
        assertThat(rejected).allSatisfy(r ->
                assertThat(r.headers().firstValue(headerName))
                        .as("response headers: %s", r.headers().map())
                        .hasValue(expectedValue));
    }
}
