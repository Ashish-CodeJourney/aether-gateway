## AC1-AC9 scorecard

PRD section 4.1. Every criterion below has a real measured value - per
this phase's own exit criterion, an unmeasured criterion is not
acceptable, but a measured-and-below-target one (a documented gap) is.
4 of 9 criteria meet their target; the rest are honestly documented
below with the real measured number and, where applicable, why.

| AC | Criterion | Target | Measured | Status |
|---|---|---|---|---|
| AC1 | Gateway overhead on a cache-miss passthrough | p95 < 15 ms above upstream latency | 6.24 ms (7.78 ms gateway p95 - 1.54 ms upstream p95, n=100) | MET |
| AC2 | Cache-hit end-to-end latency | p95 < 50 ms | 8.9 ms (n=100, confirmed EXACT_HIT via X-Aether-Cache) | MET |
| AC3 | Semantic cache hit rate on the benchmark corpus | ≥ 35% at chosen threshold | 13.16% guarded hit rate at threshold 0.94 (experiment 2, full corpus) | NOT MET (documented gap, same finding as Phase 07/M4) |
| AC4 | Semantic cache false-hit rate | ≤ 5%, hand-labelled, documented | 8.0% guarded false-hit rate at threshold 0.94 (experiment 2, full corpus) | NOT MET (documented gap, same finding as Phase 07/M4) |
| AC5 | Concurrent streaming connections on 2 vCPU / 4 GB | ≥ 2,000 | 37.1% request failure rate at 2,000 concurrent VUs; p95/p99 already in the tens of seconds at 1,200 concurrent VUs (0% hard failures there, but severe queueing) - see ac5-concurrency.json for the caveat about shared test-host confounds | NOT MET |
| AC6 | Failover time from provider outage detection | < 500 ms | p50 38.70 ms, p95 66.89 ms, p99 67.92 ms, max 70.10 ms over 100 independent trials (experiment 7) | MET |
| AC7 | Broken streams during rolling K8s deploy | 0 out of ≥ 500 in-flight | Cannot be measured: requires a running Kubernetes deployment (Phase 11/M8), which is optional and has not been built. Disclosed as an explicit, unmeasured gap rather than silently omitted or claimed via an unrelated scenario. | NOT MEASURED (blocked on Phase 11/M8) |
| AC8 | Quota accuracy under 100 concurrent requests | 0 over-issue | Exactly 50 Allowed / 50 Rejected against a 50-token budget under 100 concurrent requests, 0 over-issue, 0 under-issue (Phase 06/M3, RedisQuotaAdapterConcurrencyIntegrationTest, Testcontainers-Redis) | MET |
| AC9 | Line coverage on core modules | ≥ 75% | gateway-core 75.7%, gateway-router 76.5%, gateway-quota 73.9%, gateway-cache 81.3% (aggregate 76.8%, 482/628 lines, JaCoCo, unit + integration test execution data merged) | MET in aggregate; gateway-quota individually below target (73.9%) |

**AC3/AC4** (cache hit rate / false-hit rate) are the two criteria this
phase's own data least flatters: the corpus was hand-labelled and the
threshold was chosen from a real measured sweep (experiments 1/2), but
the resulting hit rate is well below the 35% target and the false-hit
rate is above the 5% target, at the threshold that best balances the
two. `docs/design/cache-correctness.md` (Phase 07) already documents
why: the entity/numeric guard has no concept of negation or polarity,
so several adversarial pairs that flip meaning through negation alone
pass through as false hits regardless of threshold.

**AC5** is a genuine, newly-measured gap this phase: the gateway does
not cleanly sustain 2,000 concurrent streaming connections on a
2 vCPU / 4 GB constraint in this environment - see the row above and
`ac5-concurrency.json` for the full caveat about the shared,
non-dedicated test host this was measured on.

**AC7** cannot be measured without Kubernetes (Phase 11/M8, optional,
not built) - disclosed rather than hidden.
