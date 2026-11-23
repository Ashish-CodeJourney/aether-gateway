package com.aether.gateway.quota.inmemory;

import com.aether.gateway.core.domain.ApiKeyContext;
import com.aether.gateway.quota.ApiKeyStoreContract;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.InstantSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryApiKeyStoreTest extends ApiKeyStoreContract {

    private final InMemoryApiKeyStore store = new InMemoryApiKeyStore(InstantSource.fixed(Instant.parse("2026-11-01T00:00:00Z")));

    @Override
    protected ApiKeyStore store() {
        return new ApiKeyStore(store, store);
    }

    @Test
    void aBootstrapKeyChosenByTheOperatorIsFoundByItsRawKey() {
        var summary = store.registerBootstrapKey("bootstrap", "aeth_operator-chosen-key");

        assertThat(store.findByRawKey("aeth_operator-chosen-key"))
                .contains(new ApiKeyContext(summary.id(), null, null, null));
        assertThat(summary.keyPrefix()).isEqualTo("aeth_operato");
        assertThat(store.list()).singleElement().satisfies(k -> assertThat(k.name()).isEqualTo("bootstrap"));
    }

    @Test
    void keysCreatedAtTheSameInstantStillListNewestFirst() {
        var first = store.create("first", List.of(), null, null, null);
        var second = store.create("second", List.of(), null, null, null);

        assertThat(store.list()).extracting(k -> k.id()).containsExactly(second.summary().id(), first.summary().id());
    }
}
