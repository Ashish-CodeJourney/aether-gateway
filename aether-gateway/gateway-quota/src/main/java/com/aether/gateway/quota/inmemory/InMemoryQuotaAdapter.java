package com.aether.gateway.quota.inmemory;

import com.aether.gateway.core.domain.ApiKeyContext;
import com.aether.gateway.core.domain.QuotaDecision;
import com.aether.gateway.core.domain.SoftLimitCalculator;
import com.aether.gateway.core.port.QuotaPort;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * ADR-010 lite mode: F5 quota enforcement for a single process. Each
 * key's RPS bucket, concurrency slots and monthly budget live in one
 * immutable {@link KeyState}, replaced atomically per key by
 * {@link ConcurrentHashMap#compute}, so the RPS, concurrency and budget
 * checks happen as one step and a rejection never leaves anything
 * partially acquired. There is no store to lose, so unlike the Redis
 * adapter there is no fail-closed path. Limits are per process: N
 * replicas would enforce N times every limit.
 *
 * <p>Mirrors the Redis scripts' safety nets: a reservation that is never
 * reconciled is forgotten after five minutes (its estimate stays
 * charged), a concurrency slot that is never released frees itself
 * after five minutes, and the budget counter starts afresh each UTC
 * month.
 */
public class InMemoryQuotaAdapter implements QuotaPort {

    private static final Duration RESERVATION_TTL = Duration.ofMinutes(5);
    private static final Duration CONCURRENCY_SLOT_TTL = Duration.ofMinutes(5);

    private record Reservation(long tokens, Instant expiresAt) {
    }

    private record KeyState(
            TokenBucket bucket,
            Map<String, Instant> slotExpiries,
            YearMonth month,
            long usedThisMonth,
            Map<String, Reservation> reservations) {

        static KeyState empty(Instant now) {
            return new KeyState(null, Map.of(), monthOf(now), 0, Map.of());
        }

        KeyState expiredAt(Instant now) {
            YearMonth currentMonth = monthOf(now);
            return new KeyState(
                    bucket,
                    onlyUnexpired(slotExpiries, expiry -> expiry.isAfter(now)),
                    currentMonth,
                    currentMonth.equals(month) ? usedThisMonth : 0,
                    onlyUnexpired(reservations, reservation -> reservation.expiresAt().isAfter(now)));
        }

        KeyState withBucket(TokenBucket newBucket) {
            return new KeyState(newBucket, slotExpiries, month, usedThisMonth, reservations);
        }

        KeyState withSlot(String requestId, Instant expiresAt) {
            return new KeyState(bucket, with(slotExpiries, requestId, expiresAt), month, usedThisMonth, reservations);
        }

        KeyState withoutSlot(String requestId) {
            return new KeyState(bucket, without(slotExpiries, requestId), month, usedThisMonth, reservations);
        }

        KeyState withReservation(String requestId, Reservation reservation) {
            return new KeyState(bucket, slotExpiries, month, usedThisMonth + reservation.tokens(),
                    with(reservations, requestId, reservation));
        }

        KeyState reconciled(String requestId, long actualTokens) {
            long reserved = reservations.containsKey(requestId) ? reservations.get(requestId).tokens() : 0;
            return new KeyState(bucket, slotExpiries, month, usedThisMonth + actualTokens - reserved,
                    without(reservations, requestId));
        }

        long remaining(Long budget) {
            return budget == null ? 0 : Math.max(0, budget - usedThisMonth);
        }
    }

    private record Transition(KeyState state, QuotaDecision decision) {
    }

    private final InstantSource clock;
    private final Map<String, KeyState> states = new ConcurrentHashMap<>();

    public InMemoryQuotaAdapter(InstantSource clock) {
        this.clock = clock;
    }

    @Override
    public QuotaDecision checkAndReserve(ApiKeyContext key, String requestId, long estimatedTokens) {
        Instant now = clock.instant();
        AtomicReference<QuotaDecision> decision = new AtomicReference<>();
        states.compute(key.keyId(), (ignored, current) -> {
            Transition transition = admit(stateOrEmpty(current, now), key, requestId, estimatedTokens, now);
            decision.set(transition.decision());
            return transition.state();
        });
        return decision.get();
    }

    private static Transition admit(KeyState state, ApiKeyContext key, String requestId, long estimatedTokens, Instant now) {
        Long budget = key.monthlyTokenBudget();
        KeyState afterRps = state;
        if (key.rpsLimit() != null) {
            TokenBucket bucket = (state.bucket() == null ? TokenBucket.full(key.rpsLimit(), now) : state.bucket())
                    .refilled(key.rpsLimit(), now);
            if (!bucket.hasToken()) {
                return new Transition(state.withBucket(bucket), new QuotaDecision.Rejected("rps_exceeded", state.remaining(budget)));
            }
            afterRps = state.withBucket(bucket.withOneTokenTaken());
        }

        if (key.concurrencyLimit() != null && afterRps.slotExpiries().size() >= key.concurrencyLimit()) {
            return new Transition(afterRps, new QuotaDecision.Rejected("concurrency_exceeded", afterRps.remaining(budget)));
        }

        if (budget != null && afterRps.usedThisMonth() + estimatedTokens > budget) {
            return new Transition(afterRps, new QuotaDecision.Rejected("budget_exceeded", afterRps.remaining(budget)));
        }

        KeyState withSlot = key.concurrencyLimit() == null
                ? afterRps
                : afterRps.withSlot(requestId, now.plus(CONCURRENCY_SLOT_TTL));
        if (budget == null) {
            return new Transition(withSlot, new QuotaDecision.Allowed(requestId, Long.MAX_VALUE, false));
        }

        KeyState reserved = withSlot.withReservation(requestId, new Reservation(estimatedTokens, now.plus(RESERVATION_TTL)));
        long remaining = reserved.remaining(budget);
        return new Transition(reserved,
                new QuotaDecision.Allowed(requestId, remaining, SoftLimitCalculator.isNearMonthlyLimit(remaining, budget)));
    }

    @Override
    public void reconcile(String keyId, String requestId, long actualTokens) {
        Instant now = clock.instant();
        states.compute(keyId, (ignored, current) -> stateOrEmpty(current, now).reconciled(requestId, actualTokens));
    }

    @Override
    public void releaseConcurrencySlot(String keyId, String requestId) {
        states.computeIfPresent(keyId, (ignored, current) -> current.withoutSlot(requestId));
    }

    private static KeyState stateOrEmpty(KeyState current, Instant now) {
        return current == null ? KeyState.empty(now) : current.expiredAt(now);
    }

    private static YearMonth monthOf(Instant instant) {
        return YearMonth.from(instant.atZone(ZoneOffset.UTC));
    }

    private static <V> Map<String, V> onlyUnexpired(Map<String, V> entries, java.util.function.Predicate<V> live) {
        return entries.entrySet().stream()
                .filter(entry -> live.test(entry.getValue()))
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static <V> Map<String, V> with(Map<String, V> entries, String key, V value) {
        Map<String, V> copy = new HashMap<>(entries);
        copy.put(key, value);
        return Map.copyOf(copy);
    }

    private static <V> Map<String, V> without(Map<String, V> entries, String key) {
        Map<String, V> copy = new HashMap<>(entries);
        copy.remove(key);
        return Map.copyOf(copy);
    }
}
