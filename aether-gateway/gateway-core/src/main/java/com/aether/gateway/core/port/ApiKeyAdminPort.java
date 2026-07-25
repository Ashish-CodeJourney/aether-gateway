package com.aether.gateway.core.port;

import com.aether.gateway.core.domain.ApiKeySummary;
import com.aether.gateway.core.domain.CreatedApiKey;

import java.util.List;
import java.util.Optional;

/** F8.1: full API key CRUD, deferred from Phase 06 (M3) which only needed {@link ApiKeyLookupPort}'s read slice. */
public interface ApiKeyAdminPort {

    CreatedApiKey create(String name, List<String> tags, Integer rpsLimit, Integer concurrencyLimit, Long monthlyTokenBudget);

    List<ApiKeySummary> list();

    Optional<ApiKeySummary> update(String id, Boolean enabled, Integer rpsLimit, Integer concurrencyLimit, Long monthlyTokenBudget);

    boolean delete(String id);
}
