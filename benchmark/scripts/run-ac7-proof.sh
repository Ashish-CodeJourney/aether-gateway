#!/usr/bin/env bash
# AC7 proof test: 500 concurrent SSE streams against the real gateway
# Kubernetes Service, with a rolling update triggered mid-flight.
# Requires: a running kind/k3s cluster with k8s/ already applied
# (namespace aether-gateway), and mock-primary reachable for config.
# See docs/design/kubernetes-deployment.md for why this runs in-cluster
# rather than via `kubectl port-forward` (port-forward pins its tunnel
# to one backing pod and breaks the instant that pod terminates,
# regardless of whether the rollout itself is graceful).
set -euo pipefail

KUBECTL="${KUBECTL:-kubectl}"
NAMESPACE=aether-gateway
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "Configuring mock-primary for long-lived streams (15s/chunk, ~105s/stream)..."
"$KUBECTL" -n "$NAMESPACE" run mock-config --rm -i --restart=Never --image=curlimages/curl:8.10.1 -- \
    curl -sS -m 5 -X POST http://mock-primary:8081/_mock/config \
    -H "Content-Type: application/json" -d '{"stream_delay_ms": 15000}'

echo "Deploying load-gen pod (500 concurrent streams)..."
"$KUBECTL" -n "$NAMESPACE" delete configmap ac7-script --ignore-not-found
"$KUBECTL" -n "$NAMESPACE" create configmap ac7-script --from-file="$SCRIPT_DIR/ac7-in-cluster.sh"
"$KUBECTL" -n "$NAMESPACE" delete pod ac7-loadgen --ignore-not-found --wait=true
"$KUBECTL" apply -f "$SCRIPT_DIR/ac7-loadgen-pod.yaml"
"$KUBECTL" -n "$NAMESPACE" wait --for=condition=Ready pod/ac7-loadgen --timeout=30s

echo "Waiting for all streams to launch, then triggering a rolling update..."
"$KUBECTL" -n "$NAMESPACE" logs -f ac7-loadgen 2>&1 | while IFS= read -r line; do
    echo "$line"
    if [[ "$line" == READY_SIGNAL:* ]]; then
        sleep 2
        "$KUBECTL" -n "$NAMESPACE" rollout restart deployment/gateway
        "$KUBECTL" -n "$NAMESPACE" rollout status deployment/gateway --timeout=180s &
    fi
    if [[ "$line" == RESULT* ]]; then
        break
    fi
done

wait

echo "Cleaning up..."
"$KUBECTL" -n "$NAMESPACE" delete pod ac7-loadgen --wait=false
"$KUBECTL" -n "$NAMESPACE" delete configmap ac7-script --wait=false
"$KUBECTL" -n "$NAMESPACE" run mock-reset --rm -i --restart=Never --image=curlimages/curl:8.10.1 -- \
    curl -sS -m 5 -X POST http://mock-primary:8081/_mock/reset -H "Content-Type: application/json" -d '{}'
