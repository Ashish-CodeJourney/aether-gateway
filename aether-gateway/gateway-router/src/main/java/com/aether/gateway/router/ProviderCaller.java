package com.aether.gateway.router;

import com.aether.gateway.core.domain.ChatCompletionRequest;
import com.aether.gateway.core.domain.ProviderResponse;

/**
 * Temporary, M0-only seam: a single hardcoded call to the mock provider.
 * This is deliberately NOT the ProviderAdapter port from ADR-001 / PRD
 * F2.1, which is formalised in Phase 05 (M2) once routing and multiple
 * providers exist. Keeping this as a router-internal interface (rather
 * than putting the HTTP call directly in ChatCompletionOrchestrator)
 * keeps the orchestrator unit-testable without any network dependency,
 * per Phase 03's task 5 hexagonal boundary check.
 */
public interface ProviderCaller {
    ProviderResponse call(ChatCompletionRequest request);
}
