# ADR-008: Preview Java features isolated behind interfaces

## Context

Java 25 offers Scoped Values (final) and Structured Concurrency (still
preview) and Stable Values (still preview). Preview features require
`--enable-preview` at both compile and run time and are not guaranteed
source-compatible across JDK releases; a later JDK patch could change or
remove the preview API this project depends on, breaking the build with
no warning until an upgrade.

## Decision

Any use of a preview feature (Structured Concurrency, Stable Values) is
isolated behind an interface defined in `gateway-core`, with a
non-preview fallback implementation available. Scoped Values, final since
Java 25, are used directly without this wrapping, since they carry no
preview-instability risk; they replace `ThreadLocal` for propagating
request context (API key ID, trace ID, quota reservation ID) across
virtual threads, since `ThreadLocal` both leaks and copies poorly onto
virtual threads.

As of Phase 02, no preview feature has been used yet; this ADR records
the constraint in advance so that whichever phase first reaches for
Structured Concurrency (candidate: Phase 05's provider racing, or a
future shadow-mode fan-out) applies the isolation pattern from the start
rather than retrofitting it.

## Consequences

- A JDK patch that changes a preview API's shape only requires updating
  the fallback-isolated implementation behind the interface, never call
  sites throughout the codebase.
- The build must compile with `--enable-preview` if and when a preview
  feature is actually used; until then, no preview compiler flag is
  needed, keeping the build simpler for as long as possible.
- This ADR will be amended (or a follow-up entry added) the first time a
  preview feature is actually introduced, naming the concrete interface
  and fallback used, per Phase 04's documentation requirement if
  Structured Concurrency is used there.
