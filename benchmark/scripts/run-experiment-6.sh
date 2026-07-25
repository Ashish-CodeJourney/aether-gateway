#!/usr/bin/env bash
# Wrapper so `make bench` can run experiment 6 (compact object headers)
# unattended: runs a local gateway-proxy.jar twice, toggling
# -XX:+/-UseCompactObjectHeaders, against the docker-compose stack's
# Postgres/Redis/mock-primary/mock-fallback. See run-compact-headers.sh
# for the actual per-run measurement.
set -euo pipefail

K6_BIN="${K6_BIN:?Set K6_BIN}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GATEWAY_DIR="$(cd "$SCRIPT_DIR/../../aether-gateway" && pwd)"
RESULTS_DIR="$(cd "$SCRIPT_DIR/../results" && pwd)"
JAR="$GATEWAY_DIR/gateway-proxy/build/libs/gateway-proxy.jar"
PORT=8095

rm -f "$RESULTS_DIR/experiment-6-compact-object-headers.csv"

start_gateway() {
    local flag="$1"
    (cd "$GATEWAY_DIR" && \
        GATEWAY_PORT=$PORT \
        GATEWAY_DB_URL=jdbc:postgresql://localhost:5433/aether GATEWAY_DB_USER=postgres GATEWAY_DB_PASSWORD=postgres \
        SPRING_DATA_REDIS_HOST=localhost SPRING_DATA_REDIS_PORT=6379 \
        AETHER_CACHE_ONNX_RESOURCE_CACHE_DIR=/tmp/aether-onnx-cache-test \
        java "$flag" -Xmx1g -jar "$JAR" > /tmp/gateway-compact-headers.log 2>&1) &
    for i in $(seq 1 60); do
        if curl -sf http://localhost:$PORT/actuator/health > /dev/null 2>&1; then
            break
        fi
        sleep 2
    done
    pgrep -f "gateway-proxy.jar" | tail -1
}

for label_flag in "compact-headers-off:-XX:-UseCompactObjectHeaders" "compact-headers-on:-XX:+UseCompactObjectHeaders"; do
    label="${label_flag%%:*}"
    flag="${label_flag##*:}"
    echo "Starting gateway-proxy with $flag..."
    pid=$(start_gateway "$flag")
    echo "gateway-proxy PID=$pid, running experiment ($label)..."
    K6_BIN="$K6_BIN" GATEWAY_PID="$pid" GATEWAY_PORT=$PORT LABEL="$label" CONCURRENCY=1000 DURATION=20s \
        bash "$SCRIPT_DIR/run-compact-headers.sh"
    kill "$pid" 2>/dev/null || true
    sleep 2
done

python3 "$SCRIPT_DIR/render-compact-headers-report.py" "$RESULTS_DIR"
