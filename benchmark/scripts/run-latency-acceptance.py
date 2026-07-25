#!/usr/bin/env python3
"""AC1/AC2 (PRD section 4.1): direct measurements for the M6 AC scorecard.

AC1: gateway overhead on a cache-miss passthrough, p95 < 15ms above
upstream latency. Measured as p95(gateway-relayed latency) -
p95(direct-to-mock-primary latency) over the same request shape, N
samples each.

AC2: cache-hit end-to-end latency, p95 < 50ms. Measured by sending the
same request through the gateway repeatedly (first call is a MISS and
populates the cache; every subsequent call should be an EXACT_HIT) and
taking p95 of the HIT responses' total latency.

Writes benchmark/results/ac1-ac2-latency.json.
"""
import json
import statistics
import sys
import time
import urllib.request

GATEWAY_URL = "http://localhost:8080/v1/chat/completions"
MOCK_PRIMARY_URL = "http://localhost:8082/v1/chat/completions"
SAMPLES = 100
RESULTS_DIR = sys.argv[1] if len(sys.argv) > 1 else "benchmark/results"

NO_CACHE_BODY = json.dumps({
    "model": "mock",
    "messages": [{"role": "user", "content": "hello"}],
    "stream": False,
}).encode("utf-8")

CACHEABLE_BODY = json.dumps({
    "model": "mock",
    "messages": [{"role": "user", "content": "What is the capital of France?"}],
    "stream": False,
}).encode("utf-8")


def timed_post(url, body, headers):
    req = urllib.request.Request(url, data=body, headers=headers, method="POST")
    start = time.perf_counter()
    with urllib.request.urlopen(req, timeout=10) as resp:
        resp.read()
        elapsed_ms = (time.perf_counter() - start) * 1000
        return elapsed_ms, dict(resp.headers)


def percentile(data, p):
    data = sorted(data)
    if not data:
        return None
    k = (len(data) - 1) * p
    f_idx = int(k)
    c_idx = min(f_idx + 1, len(data) - 1)
    if f_idx == c_idx:
        return data[f_idx]
    return data[f_idx] + (data[c_idx] - data[f_idx]) * (k - f_idx)


def measure_ac1():
    upstream_latencies = []
    for _ in range(SAMPLES):
        elapsed, _ = timed_post(MOCK_PRIMARY_URL, NO_CACHE_BODY, {"Content-Type": "application/json"})
        upstream_latencies.append(elapsed)

    gateway_latencies = []
    for _ in range(SAMPLES):
        elapsed, _ = timed_post(GATEWAY_URL, NO_CACHE_BODY, {"Content-Type": "application/json", "X-Aether-No-Cache": "true"})
        gateway_latencies.append(elapsed)

    upstream_p95 = percentile(upstream_latencies, 0.95)
    gateway_p95 = percentile(gateway_latencies, 0.95)
    overhead_p95 = gateway_p95 - upstream_p95
    return {
        "upstream_p95_ms": round(upstream_p95, 2),
        "gateway_p95_ms": round(gateway_p95, 2),
        "overhead_p95_ms": round(overhead_p95, 2),
        "target_ms": 15,
        "meets_target": overhead_p95 < 15,
        "samples": SAMPLES,
    }


def measure_ac2():
    # First call: MISS, populates the cache.
    timed_post(GATEWAY_URL, CACHEABLE_BODY, {"Content-Type": "application/json"})

    hit_latencies = []
    cache_headers_seen = set()
    for _ in range(SAMPLES):
        elapsed, headers = timed_post(GATEWAY_URL, CACHEABLE_BODY, {"Content-Type": "application/json"})
        cache_headers_seen.add(headers.get("X-Aether-Cache", ""))
        hit_latencies.append(elapsed)

    hit_p95 = percentile(hit_latencies, 0.95)
    return {
        "hit_p95_ms": round(hit_p95, 2),
        "target_ms": 50,
        "meets_target": hit_p95 < 50,
        "samples": SAMPLES,
        "cache_headers_observed": sorted(cache_headers_seen),
    }


def main():
    print("Measuring AC1 (gateway overhead vs upstream)...")
    ac1 = measure_ac1()
    print(json.dumps(ac1, indent=2))

    print("Measuring AC2 (cache-hit end-to-end latency)...")
    ac2 = measure_ac2()
    print(json.dumps(ac2, indent=2))

    out = {"AC1": ac1, "AC2": ac2}
    path = f"{RESULTS_DIR}/ac1-ac2-latency.json"
    with open(path, "w") as f:
        json.dump(out, f, indent=2)
    print(f"Wrote {path}")


if __name__ == "__main__":
    main()
