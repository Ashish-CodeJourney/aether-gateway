#!/usr/bin/env python3
"""PRD section 16.2, experiment 7 / docs/plan/09-milestone-m6-benchmarking.md
task 8: 100 trials of time-from-injected-outage-to-first-successful-
failed-over-response, against the real gateway (docker compose), real
mock-primary/mock-fallback, and the real resilience4j circuit breaker
(gateway-proxy/src/main/java/.../GatewayConfig.java: slidingWindowSize=5,
minimumNumberOfCalls=3, failureRateThreshold=50%,
waitDurationInOpenState=5s).

Each trial is an independent outage-injection cycle, not a single
continuous outage: mock-primary is healed and the breaker is driven
back to CLOSED (heal + wait out waitDurationInOpenState + a few
successful warm-up calls through the real gateway) before the outage is
re-injected and the timed request is sent. Without this reset, a
naive back-to-back loop only measures one genuine "just detected the
outage" event (trial 1) followed by 99 "breaker already open, skip
straight to fallback" trials, which is a different (real, but
different) measurement - this was tried first and rejected precisely
because it collapsed the requested distribution into two points instead
of 100 independent samples.

Writes benchmark/results/experiment-7-failover-timing.{csv,md}.
"""
import json
import os
import statistics
import sys
import time
import urllib.error
import urllib.request

GATEWAY_URL = "http://localhost:8080/v1/chat/completions"
MOCK_PRIMARY_CONFIG_URL = "http://localhost:8082/_mock/config"
MOCK_PRIMARY_RESET_URL = "http://localhost:8082/_mock/reset"
TRIALS = int(os.environ.get("FAILOVER_TRIALS", "100"))
# GatewayConfig.java: waitDurationInOpenState(Duration.ofSeconds(5)).
BREAKER_OPEN_WAIT_SECONDS = 5.5
WARMUP_REQUESTS = 5
RESULTS_DIR = sys.argv[1] if len(sys.argv) > 1 else "benchmark/results"

REQUEST_BODY = json.dumps({
    "model": "mock",
    "messages": [{"role": "user", "content": "hello"}],
    "stream": False,
}).encode("utf-8")


def post_json(url, body):
    req = urllib.request.Request(url, data=body, headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(req, timeout=10) as resp:
        return resp.status, resp.read()


def send_gateway_request(timeout=10):
    req = urllib.request.Request(
        GATEWAY_URL,
        data=REQUEST_BODY,
        headers={"Content-Type": "application/json", "X-Aether-No-Cache": "true"},
        method="POST",
    )
    start = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            resp.read()
            elapsed_ms = (time.perf_counter() - start) * 1000
            return {
                "elapsed_ms": round(elapsed_ms, 2),
                "status": resp.status,
                "provider": resp.headers.get("X-Aether-Provider", ""),
                "attempts": resp.headers.get("X-Aether-Attempts", ""),
            }
    except urllib.error.HTTPError as e:
        elapsed_ms = (time.perf_counter() - start) * 1000
        return {"elapsed_ms": round(elapsed_ms, 2), "status": e.code, "provider": "", "attempts": ""}


def reset_breaker_to_closed():
    """Heal mock-primary, wait out the breaker's open-state timer, then
    send warm-up requests through the real gateway so the breaker
    actually observes enough successful calls to transition back to
    CLOSED (HALF_OPEN requires minimumNumberOfCalls successes, not just
    the wait itself)."""
    post_json(MOCK_PRIMARY_RESET_URL, b"{}")
    time.sleep(BREAKER_OPEN_WAIT_SECONDS)
    for _ in range(WARMUP_REQUESTS):
        try:
            send_gateway_request(timeout=5)
        except Exception:
            pass


def run_trial(trial_number):
    reset_breaker_to_closed()
    post_json(MOCK_PRIMARY_CONFIG_URL, json.dumps({"fail_mode": "503", "fail_rate": 1.0}).encode("utf-8"))
    result = send_gateway_request()
    result["trial"] = trial_number
    return result


def main():
    trials = []
    for i in range(1, TRIALS + 1):
        trial = run_trial(i)
        trials.append(trial)
        print(f"  trial {i}/{TRIALS}: {trial['elapsed_ms']}ms, status={trial['status']}, provider={trial['provider']}")

    post_json(MOCK_PRIMARY_RESET_URL, b"{}")

    csv_path = f"{RESULTS_DIR}/experiment-7-failover-timing.csv"
    with open(csv_path, "w") as f:
        f.write("trial,elapsed_ms,status,provider,attempts\n")
        for t in trials:
            f.write(f"{t['trial']},{t['elapsed_ms']},{t['status']},{t['provider']},{t['attempts']}\n")
    print(f"Wrote {csv_path}")

    successes = [t for t in trials if t["status"] == 200]
    failures = [t for t in trials if t["status"] != 200]
    failed_over = [t for t in successes if t["provider"] == "mock-fallback"]
    latencies = sorted(t["elapsed_ms"] for t in failed_over)

    def percentile(data, p):
        if not data:
            return None
        k = (len(data) - 1) * p
        f_idx = int(k)
        c_idx = min(f_idx + 1, len(data) - 1)
        if f_idx == c_idx:
            return data[f_idx]
        return data[f_idx] + (data[c_idx] - data[f_idx]) * (k - f_idx)

    stats_lines = [
        "| Metric | Value |",
        "|---|---|",
        f"| Trials | {TRIALS} |",
        f"| Successful, served by mock-fallback | {len(failed_over)} |",
        f"| Successful but NOT served by mock-fallback (unexpected) | {len(successes) - len(failed_over)} |",
        f"| Failed (non-200) | {len(failures)} |",
    ]
    if latencies:
        stats_lines += [
            f"| min (ms) | {min(latencies):.2f} |",
            f"| p50 (ms) | {percentile(latencies, 0.50):.2f} |",
            f"| p90 (ms) | {percentile(latencies, 0.90):.2f} |",
            f"| p95 (ms) | {percentile(latencies, 0.95):.2f} |",
            f"| p99 (ms) | {percentile(latencies, 0.99):.2f} |",
            f"| max (ms) | {max(latencies):.2f} |",
            f"| mean (ms) | {statistics.mean(latencies):.2f} |",
        ]
        if len(latencies) > 1:
            stats_lines.append(f"| stdev (ms) | {statistics.stdev(latencies):.2f} |")

    md_path = f"{RESULTS_DIR}/experiment-7-failover-timing.md"
    report = f"""# Experiment 7: failover timing

PRD section 16.2(7) / docs/plan/09-milestone-m6-benchmarking.md task 8.
Generated by `benchmark/scripts/run-failover-timing.py`, against the
real gateway (docker compose), real mock-primary/mock-fallback, and the
real resilience4j circuit breaker (`GatewayConfig.java`:
`slidingWindowSize=5, minimumNumberOfCalls=3, failureRateThreshold=50%,
waitDurationInOpenState=5s`). Per PRD section 14, real providers cannot
be made to fail on command, so this proof is built entirely against the
mock, per M2's precedent.

Each of the {TRIALS} trials is an **independent** outage-injection cycle:
before every trial, mock-primary is healed and {WARMUP_REQUESTS} warm-up
requests are sent through the real gateway (after waiting out the
breaker's {BREAKER_OPEN_WAIT_SECONDS}s open-state timer) so the breaker
genuinely returns to CLOSED, not just "enough time has passed." Only
then is the outage (`fail_mode: 503, fail_rate: 1.0`) re-injected and
the single timed request sent. This is deliberately more expensive than
a single continuous outage over 100 back-to-back requests (which was
tried first and discarded - see the script's own docstring - because it
collapses into one genuine "just detected" measurement followed by 99
"breaker already open" measurements, not 100 independent samples of the
same event).

Raw data: `experiment-7-failover-timing.csv` (per-trial elapsed time,
HTTP status, serving provider, provider retry count).

## Distribution ({len(failed_over)}/{TRIALS} trials genuinely failed over to mock-fallback)

{chr(10).join(stats_lines)}

All {TRIALS} trials are measured against a freshly-closed breaker, so
this distribution reflects the real, repeated cost of *detecting* the
outage (the failed call(s) against mock-primary, any retry backoff per
F3.3, then the successful call to mock-fallback) - not the cheaper
"breaker already knows primary is down" steady-state path.
"""
    with open(md_path, "w") as f:
        f.write(report)
    print(f"Wrote {md_path}")


if __name__ == "__main__":
    main()
