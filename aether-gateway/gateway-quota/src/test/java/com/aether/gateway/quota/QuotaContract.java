package com.aether.gateway.quota;

import com.aether.gateway.core.domain.ApiKeyContext;
import com.aether.gateway.core.domain.QuotaDecision;
import com.aether.gateway.core.port.QuotaPort;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F5 / ADR-006: what every {@link QuotaPort} promises, whichever mode
 * the gateway runs in (ADR-010). Characterised against the Redis
 * adapter in integrationTest and run unchanged against the in-memory
 * one. Every test uses its own fresh key id, so a shared store is fine.
 */
public abstract class QuotaContract {

    protected abstract QuotaPort quota();

    private static String freshKeyId() {
        return "contract-" + UUID.randomUUID();
    }

    private static String requestId() {
        return "req-" + UUID.randomUUID();
    }

    @Test
    void aKeyWithNoLimitsIsAlwaysAllowed() {
        var key = new ApiKeyContext(freshKeyId(), null, null, null);

        QuotaDecision decision = quota().checkAndReserve(key, "req-1", 1_000_000);

        assertThat(decision).isEqualTo(new QuotaDecision.Allowed("req-1", Long.MAX_VALUE, false));
    }

    @Test
    void aReservationWithinBudgetIsAllowedAndReportsWhatRemains() {
        var key = new ApiKeyContext(freshKeyId(), null, null, 1_000L);

        QuotaDecision decision = quota().checkAndReserve(key, "req-1", 300);

        assertThat(decision).isEqualTo(new QuotaDecision.Allowed("req-1", 700, false));
    }

    @Test
    void reservationsAccumulateAgainstTheBudget() {
        var key = new ApiKeyContext(freshKeyId(), null, null, 1_000L);
        quota().checkAndReserve(key, requestId(), 600);

        QuotaDecision decision = quota().checkAndReserve(key, "req-2", 300);

        assertThat(decision).isEqualTo(new QuotaDecision.Allowed("req-2", 100, true));
    }

    @Test
    void aReservationThatWouldOvershootTheBudgetIsRejected() {
        var key = new ApiKeyContext(freshKeyId(), null, null, 1_000L);
        quota().checkAndReserve(key, requestId(), 600);

        QuotaDecision decision = quota().checkAndReserve(key, requestId(), 401);

        assertThat(decision).isEqualTo(new QuotaDecision.Rejected("budget_exceeded", 400));
    }

    @Test
    void aReservationExactlyFillingTheBudgetIsAllowed() {
        var key = new ApiKeyContext(freshKeyId(), null, null, 1_000L);

        QuotaDecision decision = quota().checkAndReserve(key, "req-1", 1_000);

        assertThat(decision).isEqualTo(new QuotaDecision.Allowed("req-1", 0, true));
    }

    @Test
    void reconcilingReplacesTheReservationWithActualUsage() {
        var key = new ApiKeyContext(freshKeyId(), null, null, 1_000L);
        quota().checkAndReserve(key, "req-1", 800);

        quota().reconcile(key.keyId(), "req-1", 100);

        assertThat(quota().checkAndReserve(key, "req-2", 900))
                .isEqualTo(new QuotaDecision.Allowed("req-2", 0, true));
    }

    @Test
    void reconcilingCanChargeMoreThanWasReserved() {
        var key = new ApiKeyContext(freshKeyId(), null, null, 1_000L);
        quota().checkAndReserve(key, "req-1", 100);

        quota().reconcile(key.keyId(), "req-1", 950);

        assertThat(quota().checkAndReserve(key, requestId(), 51))
                .isEqualTo(new QuotaDecision.Rejected("budget_exceeded", 50));
    }

    @Test
    void reconcilingARequestWithNoReservationChargesTheFullActualUsage() {
        var key = new ApiKeyContext(freshKeyId(), null, null, 1_000L);

        quota().reconcile(key.keyId(), "never-reserved", 300);

        assertThat(quota().checkAndReserve(key, requestId(), 701))
                .isEqualTo(new QuotaDecision.Rejected("budget_exceeded", 700));
    }

    @Test
    void theConcurrencyCapRejectsOnceEverySlotIsHeld() {
        var key = new ApiKeyContext(freshKeyId(), null, 2, null);
        quota().checkAndReserve(key, "req-1", 1);
        quota().checkAndReserve(key, "req-2", 1);

        QuotaDecision decision = quota().checkAndReserve(key, "req-3", 1);

        assertThat(decision).isInstanceOfSatisfying(QuotaDecision.Rejected.class,
                rejected -> assertThat(rejected.reason()).isEqualTo("concurrency_exceeded"));
    }

    @Test
    void releasingASlotAdmitsTheNextRequest() {
        var key = new ApiKeyContext(freshKeyId(), null, 1, null);
        quota().checkAndReserve(key, "req-1", 1);

        quota().releaseConcurrencySlot(key.keyId(), "req-1");

        assertThat(quota().checkAndReserve(key, "req-2", 1)).isInstanceOf(QuotaDecision.Allowed.class);
    }

    @Test
    void aBudgetRejectionDoesNotLeakAConcurrencySlot() {
        var key = new ApiKeyContext(freshKeyId(), null, 1, 10L);
        quota().checkAndReserve(key, "req-1", 20);

        assertThat(quota().checkAndReserve(key, "req-2", 5)).isInstanceOf(QuotaDecision.Allowed.class);
    }

    @Test
    void theRpsLimitRejectsABurstAboveItsRate() {
        var key = new ApiKeyContext(freshKeyId(), 1, null, null);

        QuotaDecision first = quota().checkAndReserve(key, requestId(), 1);
        QuotaDecision second = quota().checkAndReserve(key, requestId(), 1);

        assertThat(first).isInstanceOf(QuotaDecision.Allowed.class);
        assertThat(second).isInstanceOfSatisfying(QuotaDecision.Rejected.class,
                rejected -> assertThat(rejected.reason()).isEqualTo("rps_exceeded"));
    }

    @Test
    void anRpsRejectionHoldsNoConcurrencySlot() {
        var key = new ApiKeyContext(freshKeyId(), 1, 1, null);
        quota().checkAndReserve(key, "req-1", 1);
        quota().checkAndReserve(key, "req-2", 1);

        quota().releaseConcurrencySlot(key.keyId(), "req-1");

        var unthrottledSameKey = new ApiKeyContext(key.keyId(), null, 1, null);
        assertThat(quota().checkAndReserve(unthrottledSameKey, "req-3", 1)).isInstanceOf(QuotaDecision.Allowed.class);
    }

    @Test
    void differentKeysHaveIndependentLimits() {
        var exhausted = new ApiKeyContext(freshKeyId(), 1, 1, 10L);
        quota().checkAndReserve(exhausted, requestId(), 10);
        var other = new ApiKeyContext(freshKeyId(), 1, 1, 10L);

        assertThat(quota().checkAndReserve(other, "req-1", 10)).isInstanceOf(QuotaDecision.Allowed.class);
    }

    /** F5.5 / M3's exit criterion: 100 concurrent requests against a 50-request budget admit exactly 50. */
    @Test
    void exactlyTheBudgetIsAdmittedUnderConcurrentLoad() throws Exception {
        var key = new ApiKeyContext(freshKeyId(), 1000, 1000, 50L);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<QuotaDecision>> calls = IntStream.range(0, 100)
                .<Callable<QuotaDecision>>mapToObj(i -> () -> {
                    start.await();
                    return quota().checkAndReserve(key, "req-" + i + "-" + UUID.randomUUID(), 1);
                })
                .toList();

        List<QuotaDecision> decisions;
        try (ExecutorService executor = Executors.newFixedThreadPool(32)) {
            List<Future<QuotaDecision>> futures = calls.stream().map(executor::submit).toList();
            start.countDown();
            decisions = futures.stream().map(QuotaContract::await).toList();
        }

        assertThat(decisions).filteredOn(QuotaDecision.Allowed.class::isInstance).hasSize(50);
        assertThat(decisions).filteredOn(QuotaDecision.Rejected.class::isInstance)
                .hasSize(50)
                .allSatisfy(d -> assertThat(((QuotaDecision.Rejected) d).reason()).isEqualTo("budget_exceeded"));
    }

    private static QuotaDecision await(Future<QuotaDecision> future) {
        try {
            return future.get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
