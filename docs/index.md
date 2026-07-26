---
slug: /
title: Introduction
sidebar_position: 1
---

# Aether Gateway

**Aether Gateway is a self-hosted control plane that sits between your applications and LLM providers.** Point an existing OpenAI-compatible SDK at it instead of at a provider directly, and it transparently handles:

- **Routing and failover** across multiple providers (Ollama, Groq, Gemini, or any OpenAI-compatible endpoint), with circuit breakers, retries, and bulkheads so a failing provider doesn't take your application down with it.
- **Semantic caching** - near-duplicate prompts are served from a vector cache (Postgres + pgvector) instead of hitting a provider again, cutting both latency and cost, with a guard against false hits (see [Semantic cache correctness](/docs/design/cache-correctness)).
- **Quota enforcement** - per-API-key rate limits, concurrency caps, and monthly token budgets, enforced atomically before a request is ever dispatched.
- **Cost accounting** - every request's real (or avoided, on a cache hit) cost is computed from a configurable per-model price sheet and exposed in response headers, logs, and a Grafana dashboard.
- **A versioned prompt registry** - name a prompt, version it, and roll a production alias back to a previous version instantly, with no gateway restart.
- **An operator console** - a small internal UI for API keys, the prompt registry, cache stats, and a request log explorer.

The engineering value here isn't in calling models - it's in everything *around* the call: streaming concurrency, distributed state, resilience patterns, cache-correctness tradeoffs, and measured performance under real Kubernetes orchestration.

## Where to go next

- **[Getting Started](/docs/getting-started)** - bring up the whole stack locally and make your first request in under a minute.
- **[Usage Guide](/docs/usage)** - the request/response contract, response headers, the prompt registry, the admin API, and the operator console.
- **[Architecture Decisions](/docs/category/architecture-decisions)** - the significant, hard-to-reverse technical decisions and the reasoning behind each.
- **[Design Docs](/docs/category/design-docs)** - narrative documentation of how specific subsystems actually work.

## Reproducing the numbers

Every benchmark result cited anywhere in these docs is real, measured, and reproducible via `make bench` against a real running stack - see [`BENCHMARKS.md`](https://github.com/Ashish-CodeJourney/aether-gateway/blob/trunk/BENCHMARKS.md) in the repository for the full tables and raw data.
