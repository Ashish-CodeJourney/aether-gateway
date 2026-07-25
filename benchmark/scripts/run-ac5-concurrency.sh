#!/usr/bin/env bash
# AC5 (PRD section 4.1): concurrent streaming connections on 2 vCPU /
# 4 GB, target >= 2,000. Constrains the running gateway container,
# restarts it under the constraint (so the JVM's own -XX:MaxRAMPercentage
# sizing reflects the real limit, not whatever was available at the
# container's original startup), then runs k6 at two concurrency levels:
# 1,200 (a calibration point) and 2,000 (the actual target), writing
# benchmark/results/ac5-concurrency.json.
set -euo pipefail

K6="${K6_BIN:?Set K6_BIN}"
RESULTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../results" && pwd)"
GATEWAY_CONTAINER="aether-gateway-gateway-1"

echo "Constraining $GATEWAY_CONTAINER to 2 vCPU / 4 GB and restarting..."
docker update --cpus=2 --memory=4g --memory-swap=4g "$GATEWAY_CONTAINER" > /dev/null
docker restart "$GATEWAY_CONTAINER" > /dev/null
for i in $(seq 1 60); do
    if curl -sf http://localhost:8080/actuator/health > /dev/null 2>&1; then
        break
    fi
    sleep 2
done

curl -sf -X POST http://localhost:8082/_mock/reset -H "Content-Type: application/json" -d '{}' > /dev/null

run_k6() {
    local concurrency="$1" summary_file="$2"
    TARGET_URL="http://localhost:8080/v1/chat/completions" CONCURRENCY="$concurrency" DURATION=20s \
        EXTRA_HEADER_NAME="X-Aether-No-Cache" EXTRA_HEADER_VALUE="true" \
        SUMMARY_FILE="$summary_file" \
        "$K6" run "$(dirname "${BASH_SOURCE[0]}")/concurrency-ceiling.js" > /dev/null
}

summary_1200=$(mktemp)
summary_2000=$(mktemp)
echo "Running k6 at 1200 concurrent VUs (calibration point)..."
run_k6 1200 "$summary_1200"
echo "Running k6 at 2000 concurrent VUs (AC5 target)..."
run_k6 2000 "$summary_2000"

echo "Restoring $GATEWAY_CONTAINER's resource limits..."
docker update --cpus=0 --memory=0 "$GATEWAY_CONTAINER" > /dev/null

python3 - "$summary_1200" "$summary_2000" "$RESULTS_DIR/ac5-concurrency.json" <<'PYEOF'
import json
import sys

s1200_path, s2000_path, out_path = sys.argv[1], sys.argv[2], sys.argv[3]
with open(s1200_path) as f:
    s1200 = json.load(f)
with open(s2000_path) as f:
    s2000 = json.load(f)

out = {
    "target_concurrent_streams": 2000,
    "constraint": "2 vCPU / 4 GB (docker --cpus=2 --memory=4g, container restarted under the limit before testing)",
    "trials": [
        {
            "concurrency": 1200, "duration": "20s",
            "http_req_failed_rate": s1200["http_req_failed_rate"],
            "p95_ms": s1200["http_req_duration_p95"], "p99_ms": s1200["http_req_duration_p99"],
            "note": "calibration point below the AC5 target",
        },
        {
            "concurrency": 2000, "duration": "20s",
            "http_req_failed_rate": s2000["http_req_failed_rate"],
            "p95_ms": s2000["http_req_duration_p95"], "p99_ms": s2000["http_req_duration_p99"],
            "note": "the AC5 target concurrency itself",
        },
    ],
    "meets_target": s2000["http_req_failed_rate"] < 0.01,
    "caveat": (
        "Measured on a shared, non-dedicated development/CI host, not an isolated benchmark "
        "machine - other processes (mock-provider, Postgres, Redis, the k6 client itself) "
        "compete for the same physical cores as the 2-vCPU-limited gateway container. Severe "
        "queueing at or below the calibration concurrency suggests a genuine capacity limit at "
        "this resource tier rather than a pure test-harness artifact, but a clean re-measurement "
        "on dedicated hardware is a reasonable follow-up before treating this as a hard product "
        "limitation."
    ),
}
with open(out_path, "w") as f:
    json.dump(out, f, indent=2)
print(f"Wrote {out_path}")
PYEOF

rm -f "$summary_1200" "$summary_2000"
