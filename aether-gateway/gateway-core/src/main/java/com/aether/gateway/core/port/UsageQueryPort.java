package com.aether.gateway.core.port;

import com.aether.gateway.core.domain.UsageAggregate;

import java.time.Instant;
import java.util.List;

/** F8.4: {@code GET /admin/usage?from=&to=&groupBy=key|provider|model|route}, built on Phase 08's request log. */
public interface UsageQueryPort {
    List<UsageAggregate> query(Instant from, Instant to, String groupBy);
}
