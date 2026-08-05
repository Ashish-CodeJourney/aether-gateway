---
title: Usage Guide
sidebar:
  order: 3
---

## Making a request

`POST /v1/chat/completions` - the standard OpenAI chat completions shape, non-streaming or streaming:

```bash
curl -X POST http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "mock",
    "messages": [{"role": "user", "content": "hello"}],
    "stream": false
  }'
```

Set `"stream": true` for a Server-Sent Events response, identical in shape to OpenAI's streaming format (`data: {...}` chunks, terminated by `data: [DONE]`). Cancelling the client connection (closing the tab, aborting the fetch) propagates upstream and stops paying for tokens - this is measured, not assumed (see the streaming design notes linked from [Architecture Decisions](/docs/category/architecture-decisions)).

`GET /v1/models` lists the models configured across every route in `routing.yaml`.

## Authentication

An `Authorization: Bearer <api-key>` header attaches the request to a real API key - its own rate limit, concurrency cap, and monthly token budget apply, and usage is attributed to it in the request log and cost dashboard.

Real API keys are created via the [admin API](#admin-api) and are stored as salted hashes, never plaintext (F9.1).

A request with no `Authorization` header - or one that doesn't resolve to a real key - is **rejected with `401 unauthenticated` by default**. Such a request is unmetered, charged to no budget, and served using the operator's own provider credentials, so a gateway reachable beyond localhost would otherwise be an open proxy to whatever those credentials can buy. An unrecognised key is treated identically to no key at all, so the endpoint can't be used to probe which keys exist.

Setting `AETHER_SECURITY_ALLOW_ANONYMOUS=true` opts back in: anonymous requests are then served unmetered against any per-key budget, but rate-limited per source IP address (F9.5) so one anonymous client can't exhaust the gateway. The Docker Compose stack and the acceptance suite both enable it, since neither has real credentials to protect; the Kubernetes manifests deliberately leave it off.

## Response headers

Every response carries headers describing exactly what happened, useful for debugging and for building your own dashboards on top:

| Header | Meaning |
|---|---|
| `X-Aether-Cache` | `MISS`, `EXACT_HIT`, or `SEMANTIC_HIT` |
| `X-Aether-Cost-USD` | Real cost for a miss, `0` for any cache hit |
| `X-Aether-Provider` | Which provider actually served this (useful for confirming failover happened) |
| `X-Aether-Attempts` | How many providers were tried before success |
| `X-Aether-Quota-Remaining` | Remaining monthly token budget for the attached API key |
| `X-Aether-Prompt-Version` | Present only when the request referenced a prompt (see below) - which version actually served it |

## Controlling the cache per request

- `X-Aether-No-Cache: true` - bypass the cache entirely for this one request (still writes the result back for future requests).
- `X-Aether-Cache-Threshold: 0.90` - override the route's configured similarity threshold for this one request, without touching `routing.yaml`.

## The prompt registry

Create and version a named prompt via the [admin API](#admin-api), then reference it from a request instead of inlining the prompt text:

```bash
curl -X POST http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "X-Aether-Prompt: support-reply@production" \
  -d '{"model": "mock", "messages": [], "variables": {"customer_name": "Alex"}}'
```

`support-reply@production` resolves the `production` alias of the `support-reply` prompt to whichever version it currently points at - repointing that alias via the admin API takes effect on the *very next* request, no gateway restart. The response carries `X-Aether-Prompt-Version` confirming which version actually served it.

## Admin API

Every `/admin/**` endpoint requires `X-Aether-Admin-Key: <key>` (fail-closed - a blank or missing configured key rejects every admin request, F8.5). The key is set via `AETHER_ADMIN_API_KEY`; the operator console (`console/`) is the friendlier way to reach all of these.

| Endpoint | Purpose |
|---|---|
| `POST /admin/api-keys` · `GET /admin/api-keys` · `DELETE /admin/api-keys/{id}` | Create, list, revoke API keys |
| `POST /admin/prompts` · `POST /admin/prompts/{name}/versions` · `PUT /admin/prompts/{name}/aliases/{alias}` | Create a prompt, add a version, repoint an alias |
| `GET /admin/routes` · `POST /admin/routes/reload` | Inspect the live routing policy; hot-reload `routing.yaml` |
| `GET /admin/providers/health` · `GET /admin/providers/{name}/breaker` | Provider health and circuit breaker state |
| `GET /admin/cache/stats` · `DELETE /admin/cache` | Cache hit-rate stats; invalidate the whole cache |
| `GET /admin/usage` | Aggregated token/cost usage |
| `GET /admin/requests` | Request log explorer (provider, cache outcome, cost, latency per request) |

## Observability

Grafana (`http://localhost:3000` under Docker Compose) ships with a pre-provisioned dashboard: request rate by route, p50/p95/p99 latency by provider, cache hit rate over time, cumulative spend vs. spend avoided, circuit breaker state timeline, in-flight streams, top API keys by cost, and error rate by provider. Prometheus (`http://localhost:9090`) scrapes `/actuator/prometheus` directly if you'd rather query the raw metrics yourself.

## Deploying

Docker Compose (above) is a complete, legitimate deployment target for a single host. For Kubernetes specifically - graceful rolling updates that don't break in-flight streams, autoscaling on the right signal, and the manifests themselves - see [Kubernetes deployment](/docs/design/kubernetes-deployment).
