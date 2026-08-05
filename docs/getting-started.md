---
title: Getting Started
sidebar:
  order: 2
---

## Prerequisites

- Docker and Docker Compose (that's it - Java, Node, and every other build tool run *inside* containers).

## Bring up the stack

From the repository root:

```bash
docker compose up -d
```

On first run this also downloads the ~90MB ONNX embedding model used for semantic caching, so give it ~30 seconds before the gateway reports healthy. This one command brings up the entire stack, seeded and ready:

| Service | Port | What it's for |
|---|---|---|
| Gateway | `8080` | The proxy itself - `/v1/*` (OpenAI-compatible API) and `/admin/*` (control plane) |
| mock-primary | `8082` | A mock provider standing in for a real one, so failover is demonstrable without needing a real outage |
| mock-fallback | `8083` | The failover target for `mock-primary` |
| Postgres + pgvector | `5433` | Semantic cache, request log, prompt registry |
| Redis | `6379` | Quotas, rate limits, circuit breaker hints |
| Prometheus | `9090` | Metrics scrape target |
| Grafana | `3000` | Pre-provisioned dashboard - cost, hit rate, breaker state, savings |

Check it's up:

```bash
curl http://localhost:8080/actuator/health
# {"status":"UP", ...}
```

## Your first request

```bash
curl -X POST http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"mock","messages":[{"role":"user","content":"hello"}]}'
```

No API key needed for this - the Compose stack sets `AETHER_SECURITY_ALLOW_ANONYMOUS=true` so unauthenticated requests are served (unmetered, but rate-limited per source IP). That is a local-demo setting: anywhere else, a request without a valid key gets `401 unauthenticated` - see [Usage](/docs/usage#authentication). Point a real OpenAI-compatible SDK at the gateway by setting its `baseUrl` to `http://localhost:8080/v1`; nothing else about the client changes.

## Configuring a real provider

The default `routing.yaml` only wires up the two mock providers above, so failover is demonstrable without needing a real outage. To route to a real provider, add an entry under `providers:` - the shape is identical regardless of which one:

```yaml
providers:
  groq-main:
    baseUrl: https://api.groq.com/openai/v1
    type: groq                    # or: ollama, gemini, openai-compatible
    apiKeyEnvVar: GROQ_API_KEY    # the *name* of an env var, never the key itself
```

Then reference it from a route's `chain:`, same as the mock providers already are. `apiKeyEnvVar` is resolved from the real environment only where the adapter is actually constructed (F9.2) - `routing.yaml` itself never contains a real credential, so it's safe to commit even with real providers configured. Set the named environment variable (`GROQ_API_KEY` in this example) on the gateway process and reload the policy with no restart:

```bash
curl -X POST http://localhost:8080/admin/routes/reload \
  -H "X-Aether-Admin-Key: dev-admin-key-do-not-use-in-production"
```

A base URL that resolves to a private/internal address is rejected outright (SSRF protection, F9.3) - the reload call above returns `400` and the previous, already-validated routing policy keeps serving.

## The admin console

A small React app for API keys, the prompt registry, cache stats, and the request log:

```bash
cd console
npm install
npm run dev   # http://localhost:5173
```

It talks to the gateway's admin API using the same key configured in `docker-compose.yml` (`AETHER_ADMIN_API_KEY`, defaulting to `dev-admin-key-do-not-use-in-production` - change this before running anywhere reachable by anyone else). See [`console/README.md`](https://github.com/Ashish-CodeJourney/aether-gateway/blob/trunk/console/README.md) in the repository for details.

## Next

**[Usage Guide](/docs/usage)** - the full request/response contract, response headers, and the admin API.
