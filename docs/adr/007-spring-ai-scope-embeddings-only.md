---
title: "ADR-007: Spring AI is used only for embeddings, not for the proxy path"
slug: docs/adr/spring-ai-scope-embeddings-only
sidebar:
  order: 7
---

## Context

Spring AI offers convenient client abstractions for chat models, vector
stores, and embeddings. Using it end-to-end for the provider proxy path
would reduce code, but Spring AI normalises requests and responses to its
own domain model, silently dropping fields it does not model. A gateway's
job is to be transparent (F1.1): whatever a client sends must reach the
provider, and whatever the provider returns must reach the client, byte-
for-byte where it is not the gateway's job to change it. Spring AI's
`VectorStore` abstraction also hides HNSW index parameters (`m`,
`ef_search`) this project needs direct control over for benchmark
experiment 16.2(4).

## Decision

Use `spring-ai-transformers` for exactly one thing: in-process ONNX
embeddings (`all-MiniLM-L6-v2`, 384-dim) for the semantic cache,
introduced in Phase 07. Do not use Spring AI for the provider adapters,
the streaming relay, or the vector store. Provider adapters hand-parse
just enough of each provider's response to extract usage and route
streaming bytes through unmodified; the cache's vector storage and
similarity search are hand-built against `JdbcClient` and pgvector
directly, not through `VectorStore`.

## Consequences

- More code is owned directly (provider wire-format parsing, HNSW query
  construction) than a full Spring AI integration would require, in
  exchange for wire-level fidelity (needed for F1.1) and full control over
  cache tuning knobs (needed for the benchmark suite).
- Nobody should reach for `spring-ai`'s `ChatModel` or `VectorStore`
  abstractions out of convenience in `gateway-providers` or
  `gateway-cache`; doing so would quietly reintroduce the normalisation
  problem this ADR exists to avoid.
- This module boundary is enforced by the ArchUnit rules in
  `ModuleDependencyDirectionTest`, which fail the build if a new adapter
  crosses it - see [Module boundaries](../design/module-boundaries.md).
