// Experiment 5 of the M6 benchmarking milestone (docs/design/requirements.md)
// task 6: drives the same streaming chat-completion workload against
// two targets (WebFlux gateway-proxy vs the MVC+virtual-threads
// comparison app) so their connection/latency behaviour can be
// compared. Target and concurrency are passed via environment
// variables so the same script runs both sides.
import http from "k6/http";
import { check } from "k6";

const TARGET_URL = __ENV.TARGET_URL;
const CONCURRENCY = parseInt(__ENV.CONCURRENCY || "100", 10);
const DURATION = __ENV.DURATION || "20s";
const EXTRA_HEADER_NAME = __ENV.EXTRA_HEADER_NAME || "";
const EXTRA_HEADER_VALUE = __ENV.EXTRA_HEADER_VALUE || "";

export const options = {
    scenarios: {
        sustained: {
            executor: "constant-vus",
            vus: CONCURRENCY,
            duration: DURATION,
        },
    },
    thresholds: {
        http_req_failed: ["rate>=0"], // always compute the metric; no pass/fail gate
    },
    summaryTrendStats: ["avg", "min", "med", "p(90)", "p(95)", "p(99)", "max"],
};

const payload = JSON.stringify({
    model: "mock",
    messages: [{ role: "user", content: "hello" }],
    stream: true,
});

export default function () {
    const headers = { "Content-Type": "application/json" };
    if (EXTRA_HEADER_NAME) {
        headers[EXTRA_HEADER_NAME] = EXTRA_HEADER_VALUE;
    }
    const response = http.post(TARGET_URL, payload, { headers, timeout: "30s" });
    check(response, { "status is 200": (r) => r.status === 200 });
}

export function handleSummary(data) {
    const out = {
        concurrency: CONCURRENCY,
        duration: DURATION,
        target: TARGET_URL,
        iterations: data.metrics.iterations ? data.metrics.iterations.values.count : 0,
        http_req_failed_rate: data.metrics.http_req_failed ? data.metrics.http_req_failed.values.rate : null,
        http_req_duration_p95: data.metrics.http_req_duration ? data.metrics.http_req_duration.values["p(95)"] : null,
        http_req_duration_p99: data.metrics.http_req_duration ? data.metrics.http_req_duration.values["p(99)"] : null,
        http_req_duration_avg: data.metrics.http_req_duration ? data.metrics.http_req_duration.values.avg : null,
        checks_succeeded: data.metrics.checks ? data.metrics.checks.values.passes : null,
        checks_failed: data.metrics.checks ? data.metrics.checks.values.fails : null,
    };
    return {
        [__ENV.SUMMARY_FILE || "/dev/stdout"]: JSON.stringify(out, null, 2) + "\n",
        stdout: JSON.stringify(out, null, 2) + "\n",
    };
}
