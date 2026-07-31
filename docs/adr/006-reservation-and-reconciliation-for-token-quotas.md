---
title: "ADR-006: Reservation and reconciliation for token quotas"
slug: docs/adr/reservation-and-reconciliation-for-token-quotas
sidebar:
  order: 6
---

## Context

Monthly token budget enforcement (F5.2) needs to know, before dispatching
a request to a provider, whether the key has enough budget left. But
output token count is fundamentally unknown until the provider's response
completes; a request could be approved against remaining budget and then
consume far more (or less) than expected, allowing a client to exceed
budget by racing several large requests before any of them reconcile.

## Decision

Reserve a pessimistic estimate of total token cost at request time
(input tokens, known exactly, plus a configured worst-case output-token
assumption), write it to `q:reserved:{requestId}` (Redis string, 5-minute
TTL per PRD section 12.2), and count the reservation against the monthly
budget check immediately, before dispatch. On completion, replace the
reservation with actual usage: adjust the monthly counter
(`q:tokens:{keyId}:{yyyyMM}`) by the difference between the reservation
and the real token count, then delete the reservation key. If a request
fails or crashes before reconciliation runs, the reservation's 5-minute
TTL is the safety net that prevents a permanently stuck over-reservation.

## Consequences

- Every request that passes the budget check has already had its
  worst-case cost counted against the budget, so concurrent requests
  cannot collectively overshoot budget between check and reconciliation.
- The monthly counter can be transiently pessimistic (reserved but not
  yet reconciled), which is the intended trade: better to briefly
  under-report remaining budget than to allow over-issue.
- The reservation TTL must actually be set on every write path, not just
  the happy path; this is called out explicitly as the top risk for
  Phase 06 (M3).
- Reconciliation arithmetic (reservation minus actual, applied as a delta
  to the monthly counter) is pure math and unit-testable without Redis;
  the atomicity of the underlying Redis operations is proven separately
  by the Lua script and its integration tests.
