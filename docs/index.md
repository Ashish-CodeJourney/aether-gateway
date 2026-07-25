---
slug: /
title: Aether Gateway Docs
sidebar_position: 1
---

# Aether Gateway

Full technical documentation for Aether Gateway - a self-hosted LLM gateway handling streaming proxy, multi-provider failover, semantic caching, quota enforcement, and cost observability. For the quickstart, live demo transcript, and benchmark headline numbers, see the [project README](https://github.com/Ashish-CodeJourney/Sluice#readme).

## Where to start

- **[Architecture Decisions](/category/architecture-decisions)** - the significant, hard-to-reverse technical decisions (WebFlux vs. virtual threads, local vs. shared circuit breaker state, fail-open cache vs. fail-closed quota, and more) and the reasoning behind each.
- **[Design Docs](/category/design-docs)** - narrative documentation of how specific subsystems actually work: semantic cache correctness, the cost model, Kubernetes deployment, module boundaries, and more.

## Reproducing the numbers

Every benchmark result cited anywhere in these docs is real, measured, and reproducible via `make bench` against a real running stack - see [`BENCHMARKS.md`](https://github.com/Ashish-CodeJourney/Sluice/blob/trunk/BENCHMARKS.md) for the full tables and raw data.
