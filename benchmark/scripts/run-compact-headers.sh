#!/usr/bin/env bash
# Experiment 6 of the M6 benchmarking milestone (docs/design/requirements.md)
# task 7: heap at 1,000 concurrent streams, JEP 519 (-XX:+UseCompactObjectHeaders)
# toggled on and off, same gateway-proxy JVM, same workload each time.
# Writes benchmark/results/experiment-6-compact-object-headers.{csv,md}.
#
# Prerequisites (not started by this script):
#   - postgres/redis/mock-primary/mock-fallback reachable at the ports
#     routing.yaml/application.yml default to (localhost:5433/6379/8082/8083)
#   - the target gateway-proxy JVM already running and healthy; its PID
#     and port passed in via GATEWAY_PID / GATEWAY_PORT
set -euo pipefail

K6="${K6_BIN:?Set K6_BIN to the k6 binary path}"
RESULTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../results" && pwd)"
GATEWAY_PID="${GATEWAY_PID:?Set GATEWAY_PID to the running gateway-proxy PID}"
GATEWAY_PORT="${GATEWAY_PORT:?Set GATEWAY_PORT to the running gateway-proxy port}"
LABEL="${LABEL:?Set LABEL to e.g. compact-headers-off or compact-headers-on}"
CONCURRENCY="${CONCURRENCY:-1000}"
DURATION="${DURATION:-20s}"

CSV_PATH="$RESULTS_DIR/experiment-6-compact-object-headers.csv"
if [ ! -f "$CSV_PATH" ]; then
    echo "label,concurrency,iterations,http_req_failed_rate,peak_heap_used_mb,peak_heap_committed_mb" > "$CSV_PATH"
fi

heap_used_mb() {
    jcmd "$GATEWAY_PID" GC.heap_info 2>/dev/null \
        | grep -oE 'used [0-9]+K' \
        | grep -oE '[0-9]+' \
        | awk '{sum+=$1} END {printf "%.2f\n", sum/1024}'
}

heap_committed_mb() {
    jcmd "$GATEWAY_PID" GC.heap_info 2>/dev/null \
        | grep -oE 'committed [0-9]+K' \
        | grep -oE '[0-9]+' \
        | awk '{sum+=$1} END {printf "%.2f\n", sum/1024}'
}

mem_used_samples=$(mktemp)
mem_committed_samples=$(mktemp)
(
    while true; do
        heap_used_mb >> "$mem_used_samples" 2>/dev/null || true
        heap_committed_mb >> "$mem_committed_samples" 2>/dev/null || true
        sleep 1
    done
) &
sampler_pid=$!

summary_file=$(mktemp)
TARGET_URL="http://localhost:${GATEWAY_PORT}/v1/chat/completions" \
    CONCURRENCY="$CONCURRENCY" DURATION="$DURATION" \
    EXTRA_HEADER_NAME="X-Aether-No-Cache" EXTRA_HEADER_VALUE="true" \
    SUMMARY_FILE="$summary_file" \
    "$K6" run "$(dirname "${BASH_SOURCE[0]}")/concurrency-ceiling.js" > /dev/null

kill "$sampler_pid" 2>/dev/null || true
wait "$sampler_pid" 2>/dev/null || true

peak_used=$(sort -n "$mem_used_samples" | tail -1)
peak_committed=$(sort -n "$mem_committed_samples" | tail -1)
iterations=$(python3 -c "import json; print(json.load(open('$summary_file')).get('iterations'))")
failed_rate=$(python3 -c "import json; print(json.load(open('$summary_file')).get('http_req_failed_rate'))")

echo "$LABEL,$CONCURRENCY,$iterations,$failed_rate,$peak_used,$peak_committed" >> "$CSV_PATH"
rm -f "$summary_file" "$mem_used_samples" "$mem_committed_samples"
echo "Appended $LABEL row to $CSV_PATH"
