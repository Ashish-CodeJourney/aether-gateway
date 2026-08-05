package com.aether.gateway.proxy.config;

import com.aether.gateway.observability.RequestLogPartitionMaintainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs {@link RequestLogPartitionMaintainer} at startup and daily
 * thereafter.
 *
 * <p>Daily rather than monthly on purpose: the window is months wide, so
 * a daily pass gives dozens of chances to notice and repair a gap before
 * one could ever matter. Every replica runs its own pass; the maintainer
 * is idempotent and lock-protected, so that is redundancy rather than
 * contention.
 *
 * <p>A failure here is logged and swallowed. Partition maintenance being
 * broken is serious, but it is not a reason to fail startup or kill the
 * scheduler thread - the gateway can still serve traffic, and the next
 * pass gets another attempt. The condition surfaces as a rising
 * request-log write-failure rate rather than as an outage.
 */
@Component
public class RequestLogPartitionScheduler {

    private static final Logger log = LoggerFactory.getLogger(RequestLogPartitionScheduler.class);
    private static final long ONE_DAY_MS = 24L * 60 * 60 * 1000;

    private final RequestLogPartitionMaintainer maintainer;

    public RequestLogPartitionScheduler(RequestLogPartitionMaintainer maintainer) {
        this.maintainer = maintainer;
    }

    @Scheduled(initialDelay = 0, fixedRate = ONE_DAY_MS)
    public void maintainPartitions() {
        try {
            maintainer.ensurePartitions();
        } catch (RuntimeException e) {
            log.error("request_log partition maintenance failed; writes will start failing "
                    + "once the calendar passes the last existing partition", e);
        }
    }
}
