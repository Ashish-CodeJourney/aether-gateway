# mock-provider

Standalone Spring Boot application speaking the OpenAI chat completions
wire format, controllable per-request via headers, per PRD section 14.
Not part of the gateway hexagon (see
`docs/design/module-boundaries.md`); `gateway-providers`' provider
adapters call it exactly as they would call a real provider, over HTTP.

## Why this exists

Real providers cannot be made to fail on command. Phase 05 (resilience)
and Phase 09 (benchmarks) both need deterministic, reproducible failure
injection, which only a controllable fake can give.

## Endpoint

`POST /v1/chat/completions` — non-streaming from Phase 03; streaming
(`stream: true`) added in Phase 04.

## Control headers (per-request)

| Header | Effect | Active since |
|---|---|---|
| `X-Mock-Latency` | Delay in ms before the response is sent | Phase 03 |
| `X-Mock-Fail` | `timeout` \| `429` \| `500` \| `503` \| `malformed` | Phase 03 |
| `X-Mock-Fail-Rate` | Probability (0.0-1.0) that the configured `X-Mock-Fail` mode actually triggers; omitted means always trigger | Phase 03 |
| `X-Mock-Stream-Delay` | Inter-chunk delay in ms | Accepted Phase 03, active Phase 04 |
| `X-Mock-Truncate-At` | Cut the stream after N chunks | Accepted Phase 03, active Phase 04 |
| `X-Mock-Tokens` | Deterministic completion token count, for cost tests | Phase 03 |

`timeout` mode responds after an artificial 30-second delay with a 504,
rather than hanging forever, so it resolves deterministically in tests
while still being slow enough to trigger a caller's own timeout budget.
`malformed` mode returns HTTP 200 with a deliberately broken JSON body,
to test client-side parse-error handling.

## Test-only config endpoints

The actual client request travels through the gateway, which does not
(and should not) relay `X-Mock-*` headers as a matter of course. Some
acceptance scenarios ("mock-primary is configured to return a 503 for
every request", Phase 05/06) need to configure a specific mock-provider
instance's default behaviour from outside, before the gateway ever calls
it. Two endpoints exist for exactly this, and only this:

- `POST /_mock/config` — body is a `MockControls`-shaped JSON object
  (snake_case, matching the app's global Jackson config), sets the
  default fault behaviour applied when an incoming request carries no
  `X-Mock-Fail` header.
- `POST /_mock/reset` — clears back to no configured default.

Per-request headers always override the configured default when both are
present (`DefaultControlsHolder.resolve`).

## Running standalone

```
./gradlew :mock-provider:bootRun
```

Default port `8081`, overridable via `MOCK_PROVIDER_PORT`.
