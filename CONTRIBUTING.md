# Contributing to Aether Gateway

Thanks for considering a contribution. The existing conventions below are
deliberate and reasonably strict - please follow them rather than working
around them.

## Before you start

For anything more than a trivial fix, open an issue first describing what
you want to change and why. It saves everyone time if a PR's direction gets
agreed on before the work is done.

## Development setup

```bash
docker compose up -d          # full local stack - see the Getting Started guide
cd aether-gateway
./gradlew test                # unit tests
./gradlew integrationTest     # Testcontainers-backed integration tests (needs Docker)
./gradlew :gateway-acceptance-tests:test  # full Cucumber acceptance suite
```

Full walkthrough: [Getting Started](https://ashish-codejourney.github.io/aether-gateway/docs/getting-started) / [Usage Guide](https://ashish-codejourney.github.io/aether-gateway/docs/usage).

## How this codebase is organized

Hexagonal architecture, strictly enforced by ArchUnit tests (`gateway-core`'s
`CoreHasNoFrameworkDependenciesTest` and `ModuleDependencyDirectionTest`), not
just convention:

- **`gateway-core`** - domain model and ports. Depends on nothing but the JDK.
  If you find yourself importing Spring, Reactor, or Jackson here, stop - that
  belongs in an adapter.
- **`gateway-router`** - orchestration. Depends only on `gateway-core`.
- **Driven adapters** (`gateway-providers`, `gateway-cache`, `gateway-quota`,
  `gateway-registry`, `gateway-observability`) - implement `gateway-core`
  ports. Never depend on each other or on driving adapters.
- **Driving adapters** (`gateway-proxy`, `gateway-admin`) - thin: parse,
  validate, delegate to a use case, map the result back.

See [Module boundaries](https://ashish-codejourney.github.io/aether-gateway/docs/design/module-boundaries) and [ADR-001](https://ashish-codejourney.github.io/aether-gateway/docs/adr/hexagonal-module-layout) before adding a new module or moving code between existing ones.

## Testing expectations

**Every change needs a failing test written first.** Not a project preference
- the actual practice this codebase was built with throughout, and PRs that
add production code with no corresponding test (or a test that was clearly
written after, to match the implementation) will be asked to add one.

- **Pure domain logic** (`gateway-core`): plain JUnit 5 + AssertJ, no Spring
  context, no database, no network. If your change requires spinning up
  infrastructure to unit test it, the logic under test probably belongs
  behind a port instead.
- **Adapters touching real infrastructure** (Postgres, Redis, a real HTTP
  server): integration tests using Testcontainers against the *real* thing,
  not a mock. This project has a specific, hard-earned aversion to mocking
  infrastructure in tests that are meant to prove infrastructure-facing code
  actually works - see the fail-closed Redis outage test in `gateway-quota`
  for the pattern (a real container is actually stopped mid-test).
- **End-to-end behavior**: Cucumber scenarios in `gateway-acceptance-tests`,
  run as real HTTP calls against the actual built jars as separate OS
  processes - never against gateway internals directly.
- New provider adapters (see `gateway-providers`) run against the shared
  `ProviderAdapterContractTest` suite, using a local `MockWebServer` with
  that provider's real recorded wire format, not a hand-rolled fake.

Run the full suite before opening a PR:

```bash
cd aether-gateway
./gradlew test integrationTest
./gradlew :gateway-acceptance-tests:test
```

## Code style

- No comments explaining *what* code does - names should already make that
  clear. A comment is for the *why*: a non-obvious constraint, a workaround
  for a specific bug, something that would genuinely surprise a reader.
- Small, pure functions where the logic allows it; immutable data.
- Don't add abstractions, configuration flags, or "just in case" flexibility
  for a scenario the change doesn't actually need yet.

## Docs site

`docs-site/` is Astro + Starlight, pointed directly at the real `docs/` folder (no
duplicated content) plus a real, non-templated homepage
(`docs-site/src/pages/index.astro`). If you change something under `docs/adr/`,
`docs/design/`, `docs/getting-started.md`, or `docs/usage.md`, check it builds:

```bash
cd docs-site
npm install
npm run build
```

## Commit messages and PRs

- Focus on *why*, not just *what* - the diff already shows what changed.
- Keep PRs scoped to one coherent change. If you're fixing something
  unrelated along the way, split it into its own PR.
- CI (`.github/workflows/ci.yml`) runs unit, integration, and acceptance
  tests, plus an image build/scan and a Compose smoke test - all of it needs
  to pass before merge.

## Reporting a bug

Open an issue with: what you expected, what actually happened, and the
smallest reproduction you can manage (a `curl` command against a fresh
`docker compose up` is usually enough). For a security vulnerability, see
[`SECURITY.md`](SECURITY.md) instead - please don't open a public issue for
those.
