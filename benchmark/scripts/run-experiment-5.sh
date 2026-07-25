#!/usr/bin/env bash
# Wrapper so `make bench` can run experiment 5 (concurrency ceiling)
# unattended: starts the MVC comparison app, waits for it, runs the
# real load test, then stops it. See run-concurrency-ceiling.sh for the
# actual experiment.
set -euo pipefail

K6_BIN="${K6_BIN:?Set K6_BIN}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GATEWAY_DIR="$(cd "$SCRIPT_DIR/../../aether-gateway" && pwd)"

echo "Configuring mock-primary (reset)..."
curl -sf -X POST http://localhost:8082/_mock/reset -H "Content-Type: application/json" -d '{}' > /dev/null

echo "Starting the MVC+virtual-threads comparison app..."
(cd "$GATEWAY_DIR" && MOCK_PROVIDER_URL=http://localhost:8082/v1/chat/completions ./gradlew :gateway-bench:runMvcComparisonApp > /tmp/mvc-comparison-app.log 2>&1) &

for i in $(seq 1 30); do
    if curl -sf http://localhost:8090/actuator/health > /dev/null 2>&1 || curl -sf -o /dev/null -w '%{http_code}' -X POST http://localhost:8090/v1/chat/completions -H "Content-Type: application/json" -d '{"model":"mock","messages":[{"role":"user","content":"hi"}],"stream":false}' 2>/dev/null | grep -q "^[24]"; then
        echo "MVC comparison app is up"
        break
    fi
    sleep 2
done

MVC_PID=$(pgrep -f "com.aether.gateway.bench.mvccomparison.MvcStreamingComparisonApplication" | head -1)
if [ -z "$MVC_PID" ]; then
    echo "Could not find the MVC comparison app's PID" >&2
    exit 1
fi

K6_BIN="$K6_BIN" MVC_COMPARISON_PID="$MVC_PID" bash "$SCRIPT_DIR/run-concurrency-ceiling.sh"

kill "$MVC_PID" 2>/dev/null || true
