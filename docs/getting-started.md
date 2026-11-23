---
title: Getting Started
sidebar:
  order: 2
---

## Prerequisites

- Docker and Docker Compose (that's it - Java, Node, and every other build tool run *inside* containers).

## Try it in one container (lite mode)

The quickest way to try Aether is lite mode: the whole gateway in one container, with no Postgres, no Redis and no model download.

```bash
docker run -p 8080:8080 \
  -e AETHER_LITE_BOOTSTRAP_API_KEY=aeth_try_me \
  ashishvaghelaa/aether-gateway-proxy:lite
```

`:lite` is published with each release (`:edge-lite` tracks trunk). To build it from a checkout instead, run `docker compose -f docker-compose.lite.yml up --build` (or `make lite`) from the repository root; that compose file sets the bootstrap key to `aeth_lite_local_dev_key`.

The bundled routing policy has an `echo` model that answers in-process, so this works without any provider key:

```bash
curl -sS -D - http://localhost:8080/v1/chat/completions \
  -H "Authorization: Bearer aeth_try_me" -H "Content-Type: application/json" \
  -d '{"model":"echo","messages":[{"role":"user","content":"hello"}]}'
```

The reply is `Echo: hello` with `X-Aether-Cache: MISS`. Send the same request again and you get `X-Aether-Cache: EXACT_HIT`. Add `-e OPENAI_API_KEY=...`, `-e GEMINI_API_KEY=...` or `-e ANTHROPIC_API_KEY=...` to the `docker run` and the `gpt`, `gemini` and `claude` aliases route to real providers.

If you didn't set `AETHER_ADMIN_API_KEY`, lite mode generates an admin key and prints it once in the startup log. Use it as `X-Aether-Admin-Key` on `/admin/*`.

Lite mode is for evaluation and small single-replica deployments. It is **not durable**: API keys, prompts, the request log and monthly budgets live in memory and reset on every restart. Run **one replica only**, since limits apply per process. There is no semantic cache (exact-match only). The full support matrix is in [Lite mode](/docs/design/lite-mode).

For everything else - the semantic cache, durable state, several replicas, the Grafana dashboard - use the full stack below.

## Bring up the full stack

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

Then reference it from a route's `chain:`, same as the mock providers already are. `apiKeyEnvVar` is resolved from the real environment only where the adapter is actually constructed - `routing.yaml` itself never contains a real credential, so it's safe to commit even with real providers configured. Set the named environment variable (`GROQ_API_KEY` in this example) on the gateway process and reload the policy with no restart:

```bash
curl -X POST http://localhost:8080/admin/routes/reload \
  -H "X-Aether-Admin-Key: dev-admin-key-do-not-use-in-production"
```

A base URL that resolves to a private/internal address is rejected outright (SSRF protection) - the reload call above returns `400` and the previous, already-validated routing policy keeps serving.

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
