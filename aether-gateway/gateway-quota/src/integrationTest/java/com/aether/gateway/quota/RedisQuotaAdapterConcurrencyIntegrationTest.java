package com.aether.gateway.quota;

import com.aether.gateway.core.domain.ApiKeyContext;
import com.aether.gateway.core.domain.QuotaDecision;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F5.5 / M3's literal exit criterion: "100 concurrent requests against a
 * 50-request quota -> exactly 50 succeed." Must run against real Redis
 * (Testcontainers), never a mock, since the race conditions a
 * multi-round-trip check-then-decrement would have are only observable
 * under real concurrency against a real atomic backend, per
 * TESTING-STRATEGY.md and Phase 06's task list.
 */
class RedisQuotaAdapterConcurrencyIntegrationTest {

    private static GenericContainer<?> redis;
    private static LettuceConnectionFactory connectionFactory;
    private static RedisQuotaAdapter quotaAdapter;

    @BeforeAll
    static void startRedis() {
        redis = new GenericContainer<>(DockerImageName.parse("redis:8")).withExposedPorts(6379);
        redis.start();

        connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(redis.getHost(), redis.getMappedPort(6379)));
        connectionFactory.afterPropertiesSet();

        var template = new StringRedisTemplate(connectionFactory);
        template.afterPropertiesSet();

        quotaAdapter = new RedisQuotaAdapter(
                new RedisTokenBucket(template),
                new RedisConcurrencyCap(template),
                new RedisMonthlyBudget(template));
    }

    @AfterAll
    static void stopRedis() {
        connectionFactory.destroy();
        redis.stop();
    }

    @Test
    void exactlyFiftyOfOneHundredConcurrentRequestsSucceedAgainstAFiftyRequestBudget() throws InterruptedException {
        // rpsLimit and concurrencyLimit set generously high so only the
        // monthly budget check is actually exercised by this test; each
        // request costs exactly 1 "token" against a budget of 50, so
        // "50 requests succeed" translates directly to "50 tokens of
        // budget consumed, no more, no fewer."
        var key = new ApiKeyContext(
                "m3-concurrency-test-" + UUID.randomUUID(), 1000, 1000, 50L);

        int totalRequests = 100;
        ExecutorService executor = Executors.newFixedThreadPool(32);
        CountDownLatch readyLatch = new CountDownLatch(totalRequests);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalRequests);
        AtomicInteger acceptedCount = new AtomicInteger();
        AtomicInteger rejectedCount = new AtomicInteger();
        List<QuotaDecision> allDecisions = new CopyOnWriteArrayList<>();

        for (int i = 0; i < totalRequests; i++) {
            String requestId = "req-" + i + "-" + UUID.randomUUID();
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    QuotaDecision decision = quotaAdapter.checkAndReserve(key, requestId, 1);
                    allDecisions.add(decision);
                    if (decision instanceof QuotaDecision.Allowed) {
                        acceptedCount.incrementAndGet();
                    } else {
                        rejectedCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await(10, TimeUnit.SECONDS);
        startLatch.countDown();
        boolean completed = doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).as("all 100 requests completed within the timeout").isTrue();
        assertThat(allDecisions).hasSize(totalRequests);
        assertThat(acceptedCount.get())
                .as("exactly 50 of 100 concurrent requests must succeed, no over-issue, no under-issue")
                .isEqualTo(50);
        assertThat(rejectedCount.get()).isEqualTo(50);
        assertThat(allDecisions).filteredOn(d -> d instanceof QuotaDecision.Rejected)
                .allSatisfy(d -> assertThat(((QuotaDecision.Rejected) d).reason()).isEqualTo("budget_exceeded"));
    }

    @Test
    void rpsTokenBucketRejectsABurstAboveItsRate() {
        var key = new ApiKeyContext("m3-rps-test-" + UUID.randomUUID(), 5, 1000, null);

        long accepted = IntStream.range(0, 20)
                .mapToObj(i -> quotaAdapter.checkAndReserve(key, "rps-req-" + i, 1))
                .filter(d -> d instanceof QuotaDecision.Allowed)
                .count();

        assertThat(accepted).as("an RPS limit of 5 should reject at least some of 20 rapid requests").isLessThan(20);
    }
}
