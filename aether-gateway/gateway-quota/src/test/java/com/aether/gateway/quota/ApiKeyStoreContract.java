package com.aether.gateway.quota;

import com.aether.gateway.core.domain.ApiKeyContext;
import com.aether.gateway.core.domain.ApiKeySummary;
import com.aether.gateway.core.domain.CreatedApiKey;
import com.aether.gateway.core.port.ApiKeyAdminPort;
import com.aether.gateway.core.port.ApiKeyLookupPort;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What every API key store promises, whichever mode the gateway runs in
 * (ADR-010). Characterised against the Postgres store in
 * integrationTest and run unchanged against the in-memory one. Stores
 * may be shared between tests, so every assertion is about the keys a
 * test created itself.
 */
public abstract class ApiKeyStoreContract {

    protected record ApiKeyStore(ApiKeyLookupPort lookup, ApiKeyAdminPort admin) {
    }

    protected abstract ApiKeyStore store();

    @Test
    void createReturnsTheRawKeyOnceAlongsideItsSummary() {
        CreatedApiKey created = store().admin().create("contract-key", List.of("team-a"), 10, 5, 100_000L);

        assertThat(created.rawKey()).startsWith("aeth_").hasSizeGreaterThan(30);
        assertThat(created.summary().name()).isEqualTo("contract-key");
        assertThat(created.summary().tags()).containsExactly("team-a");
        assertThat(created.summary().rpsLimit()).isEqualTo(10);
        assertThat(created.summary().concurrencyLimit()).isEqualTo(5);
        assertThat(created.summary().monthlyTokenBudget()).isEqualTo(100_000L);
        assertThat(created.summary().monthlyUsdBudget()).isNull();
        assertThat(created.summary().enabled()).isTrue();
        assertThat(created.summary().createdAt()).isNotNull();
        assertThat(created.summary().keyPrefix()).isEqualTo(created.rawKey().substring(0, 12));
        assertThat(UUID.fromString(created.summary().id())).isNotNull();
    }

    @Test
    void everyCreatedKeyIsDistinct() {
        CreatedApiKey first = store().admin().create("first", List.of(), null, null, null);
        CreatedApiKey second = store().admin().create("second", List.of(), null, null, null);

        assertThat(first.rawKey()).isNotEqualTo(second.rawKey());
        assertThat(first.summary().id()).isNotEqualTo(second.summary().id());
    }

    @Test
    void aCreatedKeyIsFoundByItsRawKeyWithItsLimits() {
        CreatedApiKey created = store().admin().create("lookup-key", List.of(), 7, 3, 5_000L);

        assertThat(store().lookup().findByRawKey(created.rawKey()))
                .contains(new ApiKeyContext(created.summary().id(), 7, 3, 5_000L));
    }

    @Test
    void absentLimitsAreNull() {
        CreatedApiKey created = store().admin().create("unlimited-key", List.of(), null, null, null);

        assertThat(store().lookup().findByRawKey(created.rawKey()))
                .contains(new ApiKeyContext(created.summary().id(), null, null, null));
    }

    @Test
    void anUnknownRawKeyIsNotFound() {
        assertThat(store().lookup().findByRawKey("aeth_never-issued-" + UUID.randomUUID())).isEmpty();
    }

    @Test
    void listIncludesCreatedKeysNewestFirst() {
        CreatedApiKey older = store().admin().create("older-" + UUID.randomUUID(), List.of(), null, null, null);
        CreatedApiKey newer = store().admin().create("newer-" + UUID.randomUUID(), List.of(), null, null, null);

        List<String> ids = store().admin().list().stream().map(ApiKeySummary::id).toList();

        assertThat(ids).containsSubsequence(newer.summary().id(), older.summary().id());
    }

    @Test
    void updateChangesOnlyTheFieldsSupplied() {
        CreatedApiKey created = store().admin().create("updatable-key", List.of("x"), 10, 5, 100_000L);

        ApiKeySummary updated = store().admin().update(created.summary().id(), null, 20, null, null).orElseThrow();

        assertThat(updated.rpsLimit()).isEqualTo(20);
        assertThat(updated.concurrencyLimit()).isEqualTo(5);
        assertThat(updated.monthlyTokenBudget()).isEqualTo(100_000L);
        assertThat(updated.enabled()).isTrue();
        assertThat(updated.name()).isEqualTo("updatable-key");
        assertThat(updated.tags()).containsExactly("x");
        assertThat(store().lookup().findByRawKey(created.rawKey()))
                .contains(new ApiKeyContext(created.summary().id(), 20, 5, 100_000L));
    }

    @Test
    void updateOfAnUnknownIdIsEmpty() {
        assertThat(store().admin().update(UUID.randomUUID().toString(), false, null, null, null)).isEmpty();
    }

    @Test
    void aDisabledKeyIsNoLongerFoundButStillListed() {
        CreatedApiKey created = store().admin().create("disabled-key", List.of(), null, null, null);

        store().admin().update(created.summary().id(), false, null, null, null);

        assertThat(store().lookup().findByRawKey(created.rawKey())).isEmpty();
        assertThat(store().admin().list())
                .filteredOn(k -> k.id().equals(created.summary().id()))
                .singleElement()
                .satisfies(k -> assertThat(k.enabled()).isFalse());
    }

    @Test
    void aReEnabledKeyIsFoundAgain() {
        CreatedApiKey created = store().admin().create("re-enabled-key", List.of(), null, null, null);
        store().admin().update(created.summary().id(), false, null, null, null);

        store().admin().update(created.summary().id(), true, null, null, null);

        assertThat(store().lookup().findByRawKey(created.rawKey())).isPresent();
    }

    @Test
    void deleteRemovesTheKeyFromListAndLookup() {
        CreatedApiKey created = store().admin().create("deletable-key", List.of(), null, null, null);

        assertThat(store().admin().delete(created.summary().id())).isTrue();

        assertThat(store().lookup().findByRawKey(created.rawKey())).isEmpty();
        assertThat(store().admin().list()).noneSatisfy(k -> assertThat(k.id()).isEqualTo(created.summary().id()));
    }

    @Test
    void deleteOfAnUnknownIdReportsNothingDeleted() {
        assertThat(store().admin().delete(UUID.randomUUID().toString())).isFalse();
    }
}
