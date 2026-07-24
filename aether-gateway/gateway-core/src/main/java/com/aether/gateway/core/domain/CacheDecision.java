package com.aether.gateway.core.domain;

/**
 * F4.9: the cache decision recorded on every request, mapping directly
 * to the {@code X-Aether-Cache} response header (PRD section 13.1):
 * {@code MISS} / {@code EXACT_HIT} / {@code SEMANTIC_HIT} / {@code BYPASS}.
 */
public sealed interface CacheDecision
        permits CacheDecision.ExactHit, CacheDecision.SemanticHit, CacheDecision.Miss, CacheDecision.Bypass {

    String headerValue();

    record ExactHit(String cacheEntryId, String responseBodyJson) implements CacheDecision {
        @Override
        public String headerValue() {
            return "EXACT_HIT";
        }
    }

    record SemanticHit(String cacheEntryId, String responseBodyJson, double similarity) implements CacheDecision {
        @Override
        public String headerValue() {
            return "SEMANTIC_HIT";
        }
    }

    record Miss() implements CacheDecision {
        @Override
        public String headerValue() {
            return "MISS";
        }
    }

    record Bypass(String reason) implements CacheDecision {
        @Override
        public String headerValue() {
            return "BYPASS";
        }
    }
}
