package com.aether.gateway.router;

import com.aether.gateway.core.domain.BreakerState;
import com.aether.gateway.core.port.ProviderAdapter;
import com.aether.gateway.router.routing.ProviderConfig;
import com.aether.gateway.router.routing.RoutingPolicyRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Flow;

import static org.assertj.core.api.Assertions.assertThat;

class RouterBreakerStatusTest {

    private static class NoopAdapter implements ProviderAdapter {
        private final String name;

        NoopAdapter(String name) {
            this.name = name;
        }

        @Override
        public String providerName() {
            return name;
        }

        @Override
        public com.aether.gateway.core.domain.ProviderResponse invoke(com.aether.gateway.core.domain.ChatCompletionRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Flow.Publisher<com.aether.gateway.core.domain.ProviderResponse.StreamChunk> invokeStreaming(com.aether.gateway.core.domain.ChatCompletionRequest request) {
            throw new UnsupportedOperationException();
        }
    }

    private RoutingPolicyRepository buildRepository() throws IOException {
        Path path = Files.createTempFile("routing-", ".yaml");
        Files.writeString(path, """
                providers:
                  mock-primary:
                    baseUrl: http://unused
                  mock-fallback:
                    baseUrl: http://unused
                routes:
                  - alias: mock
                    chain:
                      - provider: mock-primary
                        model: mock
                        weight: 100
                      - provider: mock-fallback
                        model: mock
                """);
        return new RoutingPolicyRepository(path, (ProviderConfig pc) -> new NoopAdapter(pc.name()));
    }

    @Test
    void reportsClosedForABreakerThatHasNeverFailed() throws Exception {
        var repository = buildRepository();
        var breakerRegistry = CircuitBreakerRegistry.ofDefaults();
        var status = new RouterBreakerStatus(repository, breakerRegistry);

        var reported = status.statusFor("mock-primary").orElseThrow();

        assertThat(reported.provider()).isEqualTo("mock-primary");
        assertThat(reported.model()).isEqualTo("mock");
        assertThat(reported.state()).isEqualTo(BreakerState.CLOSED);
    }

    @Test
    void allStatusesCoversEveryChainMemberAcrossEveryRoute() throws Exception {
        var repository = buildRepository();
        var breakerRegistry = CircuitBreakerRegistry.ofDefaults();
        var status = new RouterBreakerStatus(repository, breakerRegistry);

        var all = status.allStatuses();

        assertThat(all).extracting("provider").containsExactlyInAnyOrder("mock-primary", "mock-fallback");
    }

    @Test
    void returnsEmptyForAProviderNotPresentInAnyRoute() throws Exception {
        var repository = buildRepository();
        var breakerRegistry = CircuitBreakerRegistry.ofDefaults();
        var status = new RouterBreakerStatus(repository, breakerRegistry);

        assertThat(status.statusFor("nonexistent-provider")).isEmpty();
    }
}
