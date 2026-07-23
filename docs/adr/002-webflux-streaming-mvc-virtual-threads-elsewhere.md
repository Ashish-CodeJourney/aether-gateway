# ADR-002: WebFlux for streaming, MVC plus virtual threads elsewhere

## Context

`POST /v1/chat/completions` with `stream: true` must relay SSE chunks as
they arrive (F1.3) and, critically, cancel the upstream provider call
within 100ms of client disconnect (F1.4), the single hardest and highest-
value requirement in the PRD. Every other path (non-streaming completion,
admin endpoints, cache writes) has no comparable cancellation requirement
and benefits more from straightforward, debuggable blocking code.

Two viable designs exist project-wide: WebFlux/Reactor everywhere, or
virtual threads everywhere. Picking one globally is wrong for this
system.

## Decision

Pick per path, not globally. The streaming path uses WebFlux/Reactor
specifically because a cancelled reactive chain propagates cancellation
to its upstream subscription automatically and deterministically, which
is exactly F1.4's requirement. Every other path (non-streaming
completion, admin, cache writes) uses Spring MVC with virtual threads:
far simpler to read and debug, and virtual threads remove the
thread-per-request scaling concern without needing Reactor's mental
model where cancellation semantics are not the point.

Virtual threads solve *scalability*, not *cancellation semantics*.
Detecting client disconnect on a blocking servlet stream means writing
and checking for `IOException` on write, which is workable but coarse
compared to a first-class cancellation signal. This is the answer to
"why not just virtual threads everywhere," and it is worth being able to
say plainly (PRD section 21, item 8).

## Consequences

- Two concurrency models coexist in the same application, which is more
  cognitively demanding for a new contributor than a single uniform
  model, but each model is used exactly where its strength matters.
- The streaming controller in `gateway-proxy` is built on
  `Flux`/`Mono` and Spring WebFlux; the non-streaming controller and
  admin endpoints use standard `@RestController` MVC with the platform
  thread pool backed by virtual threads
  (`spring.threads.virtual.enabled=true`).
- If Structured Concurrency (preview, ADR-008) is later used to race a
  cancellation watcher against the upstream call, it must be isolated
  behind an interface with a non-preview fallback, since it is a preview
  feature independent of this WebFlux/MVC split.
