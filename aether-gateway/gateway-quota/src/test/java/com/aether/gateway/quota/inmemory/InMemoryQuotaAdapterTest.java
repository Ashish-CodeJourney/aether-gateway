package com.aether.gateway.quota.inmemory;

import com.aether.gateway.core.domain.ApiKeyContext;
import com.aether.gateway.core.domain.QuotaDecision;
import com.aether.gateway.core.port.QuotaPort;
import com.aether.gateway.quota.QuotaContract;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryQuotaAdapterTest extends QuotaContract {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-30T23:50:00Z"));
    private final InMemoryQuotaAdapter adapter = new InMemoryQuotaAdapter(clock);

    @Override
    protected QuotaPort quota() {
        return adapter;
    }

    @Test
    void theRpsBucketRefillsContinuously() {
        var key = new ApiKeyContext("refill", 2, null, null);
        adapter.checkAndReserve(key, "r1", 1);
        adapter.checkAndReserve(key, "r2", 1);
        assertThat(adapter.checkAndReserve(key, "r3", 1)).isInstanceOf(QuotaDecision.Rejected.class);

        clock.advance(Duration.ofMillis(500));

        assertThat(adapter.checkAndReserve(key, "r4", 1)).isInstanceOf(QuotaDecision.Allowed.class);
        assertThat(adapter.checkAndReserve(key, "r5", 1)).isInstanceOf(QuotaDecision.Rejected.class);
    }

    @Test
    void theRpsBucketNeverHoldsMoreThanOneSecondOfTokens() {
        var key = new ApiKeyContext("capped", 2, null, null);
        adapter.checkAndReserve(key, "r0", 1);

        clock.advance(Duration.ofMinutes(10));

        long admitted = java.util.stream.IntStream.range(0, 5)
                .mapToObj(i -> adapter.checkAndReserve(key, "r" + (i + 1), 1))
                .filter(QuotaDecision.Allowed.class::isInstance)
                .count();
        assertThat(admitted).isEqualTo(2);
    }

    @Test
    void theMonthlyBudgetStartsAfreshInANewUtcMonth() {
        var key = new ApiKeyContext("rollover", null, null, 100L);
        adapter.checkAndReserve(key, "november", 100);
        assertThat(adapter.checkAndReserve(key, "still-november", 1)).isInstanceOf(QuotaDecision.Rejected.class);

        clock.advance(Duration.ofMinutes(11));

        assertThat(adapter.checkAndReserve(key, "december", 100))
                .isEqualTo(new QuotaDecision.Allowed("december", 0, true));
    }

    @Test
    void aReservationNeverReconciledStaysChargedButIsForgottenAfterFiveMinutes() {
        var key = new ApiKeyContext("expiry", null, null, 100L);
        adapter.checkAndReserve(key, "crashed", 80);

        clock.advance(Duration.ofMinutes(6));
        adapter.reconcile(key.keyId(), "crashed", 10);

        assertThat(adapter.checkAndReserve(key, "next", 11)).isEqualTo(new QuotaDecision.Rejected("budget_exceeded", 10));
        assertThat(adapter.checkAndReserve(key, "next", 10)).isInstanceOf(QuotaDecision.Allowed.class);
    }

    @Test
    void aConcurrencySlotNeverReleasedFreesItselfAfterFiveMinutes() {
        var key = new ApiKeyContext("leaked-slot", null, 1, null);
        adapter.checkAndReserve(key, "crashed", 1);
        assertThat(adapter.checkAndReserve(key, "blocked", 1)).isInstanceOf(QuotaDecision.Rejected.class);

        clock.advance(Duration.ofMinutes(6));

        assertThat(adapter.checkAndReserve(key, "admitted", 1)).isInstanceOf(QuotaDecision.Allowed.class);
    }

    @Test
    void anRpsRejectionReportsTheBudgetStillRemaining() {
        var key = new ApiKeyContext("rps-remaining", 1, null, 100L);
        adapter.checkAndReserve(key, "r1", 30);

        assertThat(adapter.checkAndReserve(key, "r2", 1)).isEqualTo(new QuotaDecision.Rejected("rps_exceeded", 70));
    }
}
