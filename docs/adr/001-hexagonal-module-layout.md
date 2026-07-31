---
title: "ADR-001: Hexagonal module layout"
slug: docs/adr/hexagonal-module-layout
sidebar:
  order: 1
---

## Context

The gateway sits between clients and multiple upstream LLM providers, and
must support swapping the mock provider for real providers (Phase 12),
testing business rules (routing, resilience, quotas, caching) without a
Spring context or network calls, and driving the system in behavioral
tests exactly as a real client would, through HTTP only. A conventional
layered architecture (controller -> service -> repository) does not draw
a hard enough boundary to guarantee any of these three properties; it is
easy for a "service" layer to quietly depend on a JDBC type or an HTTP
client, at which point business logic can no longer be tested without
that infrastructure.

## Decision

Adopt a hexagonal (ports and adapters) layout with the exact module list
from PRD section 6:

`gateway-core`, `gateway-proxy`, `gateway-router`, `gateway-providers`,
`gateway-cache`, `gateway-quota`, `gateway-registry`,
`gateway-observability`, `gateway-admin`, `gateway-bench`,
`mock-provider`.

`gateway-core` depends on nothing but the JDK. It contains the domain
model (records, sealed interfaces), driving ports (interfaces the outside
world calls into, e.g. a chat-completion use case), and driven ports
(interfaces the domain calls out through, e.g. `ProviderAdapter`,
`CachePort`, `QuotaPort`). Every other module depends inward toward
`gateway-core`, never the reverse. `gateway-router` implements the
driving ports by orchestrating driven ports and is the one place
Resilience4j annotations are allowed to sit close to orchestration logic
(see ADR-003). Driving adapters (`gateway-proxy`, `gateway-admin`,
`gateway-bench`) are thin: parse, validate, delegate to a driving port,
map the result back. Driven adapters (`gateway-providers`,
`gateway-cache`, `gateway-quota`, `gateway-registry`,
`gateway-observability`) implement driven ports against real
infrastructure. `mock-provider` is not a module inside the hexagon; it is
a standalone Spring Boot application that `gateway-providers`'
`MockProviderAdapter` calls out to over HTTP, exactly as it would call a
real provider.

This dependency direction is enforced mechanically by ArchUnit from
Phase 03 onward (`gateway-core` must import nothing from
`org.springframework.*` or any adapter package), not left as a convention
that erodes under deadline pressure.

The deployable Spring Boot application (the single runnable jar that
serves both `/v1/*` and `/admin/*`) is assembled in `gateway-proxy`: it
is the module Spring Boot's component scan roots from, depending on
`gateway-router`, `gateway-providers`, `gateway-quota`, `gateway-cache`,
`gateway-registry`, `gateway-observability`, and `gateway-admin` at
runtime. This is not a new module; it keeps the module list exactly as
PRD section 6 specifies, and matches the common hexagonal-architecture
convention that the primary driving adapter also hosts the application
entry point.

## Consequences

- Swapping the mock provider for a real one (Phase 12) requires zero
  changes to `gateway-core` or `gateway-router`; only a new
  `gateway-providers` adapter and routing config change.
- Business rules (threshold gates, entity guard, token bucket math,
  routing predicate resolution) are unit-testable with JUnit 5 + AssertJ
  alone, no Spring context, no database, no network.
- The cost is real: more interfaces, more indirection, more files, and a
  steeper on-ramp than a simpler layered structure. This cost is paid
  deliberately and consistently, not opportunistically.
- See `docs/plan/HEXAGONAL-ARCHITECTURE-GUIDE.md` for the full mapping
  and the checklist re-run whenever a module or adapter is added.
