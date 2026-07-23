# Architecture diagram

Rendered form of PRD section 6's ASCII diagram, for use in the README
(PRD section 20, item 3).

```mermaid
flowchart TB
    Client["OpenAI-compatible client SDK<br/>(baseUrl override)"]

    subgraph Gateway["Aether Gateway"]
        direction TB
        AuthN["1. AuthN<br/>API key to KeyCtx"]
        Quota["2. Quota<br/>Redis token bucket, Lua, atomic"]
        Prompt["3. Prompt resolution<br/>registry, versioned"]
        Cache["4. Semantic cache lookup<br/>embed to ANN to threshold"]
        Router["5. Router<br/>policy to provider chain"]
        Resilience["6. Resilience<br/>breaker / retry / bulkhead"]
        Adapter["7. Provider adapter<br/>normalise req/resp"]
        Stream["8. Stream pump<br/>SSE relay + cancellation"]
        Accounting["9. Accounting (async)<br/>tokens, cost, cache write"]

        AuthN --> Quota --> Prompt --> Cache --> Router --> Resilience --> Adapter --> Stream --> Accounting
    end

    Redis[("Redis<br/>quotas, breaker hints")]
    Postgres[("Postgres + pgvector<br/>cache, logs, registry")]
    Ollama["Ollama (local)"]
    Groq["Groq"]
    Gemini["Gemini"]
    Mock["MockProvider<br/>(fault injection)"]

    Client --> AuthN
    Quota <--> Redis
    Cache <--> Postgres
    Adapter --> Ollama
    Adapter --> Groq
    Adapter --> Gemini
    Adapter --> Mock

    Accounting --> Micrometer["Micrometer / OTel"]
    Micrometer --> Prometheus["Prometheus"]
    Prometheus --> Grafana["Grafana"]
```

## Module layout

```mermaid
flowchart LR
    subgraph Hexagon["The hexagon"]
        Core["gateway-core<br/>domain + ports<br/>no framework deps"]
    end

    Router["gateway-router<br/>orchestration"] --> Core

    Proxy["gateway-proxy<br/>HTTP/SSE + app entrypoint"] --> Router
    Admin["gateway-admin<br/>control plane HTTP"] --> Router
    Bench["gateway-bench<br/>load gen / eval harness"] --> Core

    Providers["gateway-providers<br/>ProviderAdapter impls"] --> Core
    CacheMod["gateway-cache<br/>CachePort impl"] --> Core
    QuotaMod["gateway-quota<br/>QuotaPort impl"] --> Core
    Registry["gateway-registry<br/>PromptRegistryPort impl"] --> Core
    Observability["gateway-observability<br/>RequestLogPort impl"] --> Core

    Proxy --> Providers
    Proxy --> CacheMod
    Proxy --> QuotaMod
    Proxy --> Registry
    Proxy --> Observability
    Proxy --> Admin

    MockProvider["mock-provider<br/>standalone app"]
    Providers -. HTTP only .-> MockProvider

    Acceptance["gateway-acceptance-tests<br/>Cucumber-JVM"] -. HTTP only .-> Proxy
```

Both diagrams source from PRD section 6 and `docs/design/module-boundaries.md`; if either changes, update both this file and the source document in the same change.
