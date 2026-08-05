---
title: "ADR-009: Three-layer testing strategy, hexagonal architecture as a hard constraint"
slug: docs/adr/three-layer-testing-strategy
sidebar:
  order: 9
---

## Context

A gateway with resilience, caching, and quota logic has a large surface
of business rules that are expensive to verify through slow, flaky,
infrastructure-dependent tests alone, and an equally large surface of
adapter code whose correctness can only really be proven against real
infrastructure. A single test layer (all unit, or all integration) is
wrong for a codebase shaped like this one.

## Decision

Adopt the hexagonal-boundary and testing conventions below as binding,
not optional guidance. They are enforced mechanically - by ArchUnit and
by CI - rather than left to reviewer discretion:

1. `gateway-core` carries zero framework dependencies, enforced by
   ArchUnit from Phase 03 onward.
2. Every external dependency (database, Redis, HTTP call to a provider,
   filesystem, clock, random source) sits behind a port interface owned
   by `gateway-core`.
3. Every port that has more than one adapter implementation is proven by
   a shared contract test suite run against each implementation
   (`ProviderAdapter`'s suite runs against `MockProviderAdapter` in Phase
   05, and later `OllamaAdapter`, `GroqAdapter`, `GeminiAdapter` in Phase
   12, unchanged).
4. Every milestone's PRD-derived exit criterion (section 18) gets at
   least one tagged Cucumber-JVM scenario in a dedicated
   `gateway-acceptance-tests` module, driving the system only through its
   public HTTP/SSE surface, never reaching into internals to assert.

Concrete tooling: Cucumber-JVM on the JUnit 5 platform engine
(`cucumber-junit-platform-engine`), JUnit 5 + AssertJ for unit tests,
Testcontainers for integration tests against real Postgres+pgvector and
Redis. `gateway-acceptance-tests` is a separate Gradle module depending
only on an HTTP test client (`java.net.http.HttpClient` or a thin
wrapper) and Cucumber/JUnit test infrastructure; it must never depend on
`gateway-core` or any other internal module. Unit tests live in each
module's `src/test/java`; integration tests live in each module's
`src/integrationTest/java`, a separate Gradle source set run as a
distinct task so CI can report and gate on it separately from unit tests.

## Consequences

- Work is not done unless: every new unit of business
  logic has a unit test written first (TDD), every new driven adapter has
  a passing contract/integration test, the phase's exit criterion has a
  passing tagged Cucumber scenario, and every previous phase's Cucumber
  scenarios still pass (accumulating regression suite).
- This is more test infrastructure to stand up before any feature code
  exists (Phase 03 scaffolds all three layers on an otherwise-empty
  system). The cost is paid once, up front, rather than retrofitted.
- Architecture is a structural property (ArchUnit) distinct from
  behavior (Cucumber); both are required, neither substitutes for the
  other.
