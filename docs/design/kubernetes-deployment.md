# Kubernetes deployment (M8)

How the gateway survives a rolling update without breaking in-flight SSE
streams, per PRD section 17.2's three named things ("do them properly or
skip Kubernetes entirely") and AC7 ("500 streams survive a rolling
deploy; HPA scales on in-flight count"). Written alongside the M8 proof
test; see `benchmark/results/ac7-rolling-update.json` and this
document's "Proof test results" section for the actual numbers.

## The graceful-drain chain

Four pieces have to work together, in this order, for a rolling update
to be invisible to an in-flight stream (`k8s/10-gateway-deployment.yaml`):

1. **Pod marked Terminating -> readiness flips first.**
   `server.shutdown=graceful` and `management.health.readiness-state`
   (`gateway-proxy/application.yml`) make the readiness probe report
   DOWN the instant Kubernetes starts terminating the pod - before
   SIGTERM, before any connection is actually refused. The Service's
   endpoint controller reacts to that and stops routing *new* requests
   to this pod. Existing connections are untouched at this point.

2. **`preStop` sleeps 10s.** Endpoint removal has to propagate through
   kube-proxy (iptables rules) and any client-side connection pool
   before SIGTERM is sent, or SIGTERM can arrive while a client still
   believes the pod is routable. This sleep is pure margin against that
   propagation delay; it does nothing else.

3. **SIGTERM triggers Spring's graceful shutdown.** The server stops
   accepting new connections but lets in-flight streams finish
   naturally, up to `timeout-per-shutdown-phase` (30s). "Finish
   naturally" is only safe because of Phase 04's real cancellation
   guarantee (F1.4) - a stream that outlives the shutdown window is
   cancelled cleanly rather than left dangling.

4. **`terminationGracePeriodSeconds` (50s) is the hard ceiling.**
   preStop sleep (10s) + shutdown timeout (30s) + a 10s buffer. Any
   stream still running past this gets SIGKILLed - the proof test needs
   its streams to finish well inside this window, not right at the edge
   of it.

Readiness-before-liveness ordering matters as much as the timeouts: if
liveness flipped first, Kubernetes would kill the pod while it still had
readable in-flight streams, which is exactly the bug this chain exists
to prevent.

## Startup: two separate caches, not one

The embedding model (Phase 07/M4, `spring-ai-transformers` +
`TransformersEmbeddingModel`) needs two independent things from the
network on cold start, and only one of them was originally covered:

- **The ONNX tokenizer/model files** (`tokenizer.json`, `model.onnx`,
  ~90MB), cached via `AETHER_CACHE_ONNX_RESOURCE_CACHE_DIR` ->
  `spring-ai-transformers`'s own `ResourceCacheService`.
- **DJL's native PyTorch engine library** (`ai.djl.pytorch.jni.LibUtils`,
  the actual inference backend those files run on). This is a
  *separate* download, not covered by the env var above. DJL resolves
  its cache directory as `DJL_CACHE_DIR` -> `${user.home}/.djl.ai` (if
  writable) -> `${java.io.tmpdir}`. The container's `aether` user has no
  home directory (`useradd -r` with no `-m` in `gateway-proxy/Dockerfile`),
  so without `DJL_CACHE_DIR` set explicitly this silently fell back to
  `/tmp` - ephemeral, wiped on every container restart. That meant
  *every* pod restart re-downloaded a ~440MB native library from
  scratch, regardless of the ONNX cache above.

Found live, during this phase's own proof-test setup: with 3-4 replicas
racing to each independently download both of these from GitHub
simultaneously against this sandbox's occasionally-flaky network, cold
starts routinely timed out and crash-looped (`ConnectException:
Connection timed out`, both from `spring-ai-transformers` and
separately from DJL once the first download got further).

Fixed the same way for both: point the cache at the `onnx-cache` volume
(`hostPath` on this single-node kind cluster, so all replicas share one
downloaded copy) instead of at a per-container ephemeral path.
`DJL_CACHE_DIR=/onnx-cache/djl-cache` alongside the existing
`AETHER_CACHE_ONNX_RESOURCE_CACHE_DIR=/onnx-cache`. A real multi-node
cluster would need a ReadWriteMany volume (or, better, bake both caches
into the image at build time) for the same effect - disclosed, not
silently assumed to generalize past this proof-test cluster, matching
the same disclosure already made for the `hostPath` choice itself.

## Memory: `MaxRAMPercentage` doesn't see native memory

`-XX:MaxRAMPercentage=75` (baked into the image, `gateway-proxy/Dockerfile`)
sizes the JVM heap off the container's cgroup memory *limit*. The ONNX
Runtime and DJL/PyTorch native memory the embedding model needs sits
entirely outside that heap, on top of it. A 2Gi limit OOMKilled the
container (exit 137) reliably during model load; `docker-compose.yml`
never set an explicit memory limit, so this never surfaced there. Fixed
by raising `requests.memory: 1.5Gi`, `limits.memory: 3Gi`
(`k8s/10-gateway-deployment.yaml`). Same class of bug as Phase 08 (M5)'s
Alpine/glibc Dockerfile finding: only caught by running under the real
target orchestration, never by `java -jar` or Compose.

## Autoscaling on the right signal

These pods are I/O-bound waiting on upstream provider calls - CPU sits
near idle while genuinely saturated with concurrent streams. The HPA
(`k8s/12-gateway-hpa.yaml`) scales on `aether_gateway_in_flight_streams`
(Phase 08/M5's `MicrometerMetricsAdapter`) via the `custom.metrics.k8s.io`
API instead of CPU. **Disclosed gap**: this requires Prometheus +
Prometheus Adapter deployed in-cluster to actually serve that metric,
which was not deployed or proven live in this phase's session - the
manifest is correct and is what a real deployment would apply once the
adapter is in place, but the scale-out event itself was not exercised.

## Multi-replica circuit breaker

No new design here (ADR-004 already covers this): `replicas: 3` means 3
independent local Resilience4j breakers, each learning about a failing
provider from its own traffic only. The Redis advisory hint built in
Phase 05 is what keeps them from being wildly inconsistent with each
other. This phase exercises that existing design under real multi-replica
conditions rather than Phase 05's single-process proof; it wasn't
re-verified with a dedicated chaos scenario in this session beyond the
proof test's own traffic pattern.

## Proof test results (AC7)

Methodology note first, because it's the reason the first attempt gave a
false failure: **`kubectl port-forward svc/gateway` pins the whole
tunnel to one specific backing pod**, bypassing the Service's actual
kube-proxy load-balancing entirely. When that one pod terminates during
a rolling update, every connection through the tunnel drops at once -
regardless of whether the rollout itself is graceful. That's a test
harness artifact, not something AC7 is about, and it produced a
490/500-broken result that had nothing to do with the drain chain above.
The real proof test has to run *inside* the cluster, as a client of the
Service the way any other in-cluster caller would be.

Corrected methodology: a throwaway pod (`curlimages/curl`) inside the
cluster opens 500 concurrent SSE streams against `http://gateway:8080`
(mock-primary configured with a 15s per-chunk delay so each stream runs
~105s, comfortably spanning a rollout), a `kubectl rollout restart
deployment/gateway` is triggered a couple seconds after all 500 streams
are confirmed launched, and each stream is checked for a `data:[DONE]`
terminator versus a broken connection.

Run twice, both with the rollout genuinely overlapping the streams
(confirmed via ReplicaSet hash change and pod ages):

| Run | Streams | Broken | Rollout duration |
|-----|---------|--------|-------------------|
| 1   | 500     | 0      | ~53s |
| 2   | 500     | 0      | ~37s |

**AC7 met: 0/500 broken streams across both runs.**
