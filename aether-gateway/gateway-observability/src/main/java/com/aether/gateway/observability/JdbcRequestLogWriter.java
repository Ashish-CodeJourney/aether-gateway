package com.aether.gateway.observability;

import com.aether.gateway.core.domain.RequestLogEntry;
import com.aether.gateway.core.port.RequestLogPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.sql.Types;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * F6.2: persists request log entries asynchronously. {@link #log} only
 * ever offers to an in-memory queue and returns immediately - the
 * actual JDBC write happens on a single dedicated background thread, so
 * a slow or unavailable database can never add latency to the request
 * path this entry describes (the phase's single biggest named risk).
 * The queue is bounded: under sustained overload, this adapter drops
 * the oldest entries and logs a warning rather than growing without
 * bound and risking an OOM, since losing observability data is a far
 * smaller problem than an unbounded queue taking the process down.
 */
public class JdbcRequestLogWriter implements RequestLogPort {

    private static final Logger log = LoggerFactory.getLogger(JdbcRequestLogWriter.class);
    private static final int QUEUE_CAPACITY = 10_000;

    private final JdbcClient jdbcClient;
    private final BlockingQueue<RequestLogEntry> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final ExecutorService writerThread = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "request-log-writer");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean running = true;

    public JdbcRequestLogWriter(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
        writerThread.submit(this::drainLoop);
    }

    @Override
    public void log(RequestLogEntry entry) {
        if (!queue.offer(entry)) {
            log.warn("Request log queue full (capacity {}); dropping entry for request {}", QUEUE_CAPACITY, entry.id());
        }
    }

    public void shutdown() {
        running = false;
        writerThread.shutdownNow();
    }

    private void drainLoop() {
        while (running) {
            try {
                RequestLogEntry entry = queue.poll(1, TimeUnit.SECONDS);
                if (entry != null) {
                    writeToDatabase(entry);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                // A single bad/unwritable entry (or a transient DB
                // outage) must never kill the writer thread; log and
                // keep draining, since observability data loss is
                // strictly preferable to observability itself going
                // dark for the rest of the process's lifetime.
                log.warn("Failed to write request log entry", e);
            }
        }
    }

    private void writeToDatabase(RequestLogEntry entry) {
        jdbcClient.sql("""
                        INSERT INTO request_log (
                            id, api_key_id, trace_id, route_alias, provider, model,
                            prompt_id, prompt_version, streamed, cache_outcome, similarity,
                            input_tokens, output_tokens, cost_usd, saved_usd,
                            ttfb_ms, total_ms, attempt_count, failover_chain,
                            status, error_code, created_at
                        ) VALUES (
                            :id, :apiKeyId, :traceId, :routeAlias, :provider, :model,
                            :promptId, :promptVersion, :streamed, :cacheOutcome, :similarity,
                            :inputTokens, :outputTokens, :costUsd, :savedUsd,
                            :ttfbMs, :totalMs, :attemptCount, :failoverChain,
                            :status, :errorCode, :createdAt
                        )
                        """)
                .param("id", entry.id())
                .param("apiKeyId", entry.apiKeyId())
                .param("traceId", entry.traceId())
                .param("routeAlias", entry.routeAlias())
                .param("provider", entry.provider())
                .param("model", entry.model())
                .param("promptId", entry.promptId())
                .param("promptVersion", entry.promptVersion())
                .param("streamed", entry.streamed())
                .param("cacheOutcome", entry.cacheOutcome())
                .param("similarity", entry.similarity())
                .param("inputTokens", entry.inputTokens())
                .param("outputTokens", entry.outputTokens())
                .param("costUsd", entry.costUsd())
                .param("savedUsd", entry.savedUsd())
                .param("ttfbMs", entry.ttfbMs())
                .param("totalMs", entry.totalMs())
                .param("attemptCount", entry.attemptCount())
                .param("failoverChain", entry.failoverChain().toArray(new String[0]))
                .param("status", entry.status())
                .param("errorCode", entry.errorCode())
                .param("createdAt", Timestamp.from(entry.createdAt()))
                .update();
    }
}
