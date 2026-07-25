package com.aether.gateway.router.routing;

import com.aether.gateway.core.domain.ChatCompletionRequest;
import com.aether.gateway.core.domain.ProviderResponse;
import com.aether.gateway.core.port.ProviderAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoutingPolicyRepositoryTest {

    @TempDir
    Path tempDir;

    private static final ProviderAdapter STUB_ADAPTER = new ProviderAdapter() {
        @Override
        public String providerName() {
            return "stub";
        }

        @Override
        public ProviderResponse invoke(ChatCompletionRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Flow.Publisher<ProviderResponse.StreamChunk> invokeStreaming(ChatCompletionRequest request) {
            throw new UnsupportedOperationException();
        }
    };

    private Path writeRoutingYaml(String yaml) throws IOException {
        Path path = tempDir.resolve("routing.yaml");
        Files.writeString(path, yaml);
        return path;
    }

    @Test
    void rejectsAConfigWhoseProviderBaseUrlResolvesToAPrivateAddress() throws IOException {
        Path path = writeRoutingYaml("""
                providers:
                  evil:
                    baseUrl: http://10.0.0.5:8080
                    type: openai-compatible
                routes: []
                """);

        assertThatThrownBy(() -> new RoutingPolicyRepository(path, cfg -> STUB_ADAPTER))
                .isInstanceOf(SsrfProtectionException.class);
    }

    @Test
    void aRejectedReloadLeavesThePreviouslyLoadedGoodConfigServing() throws IOException {
        Path path = writeRoutingYaml("""
                providers:
                  good:
                    baseUrl: https://8.8.8.8
                    type: openai-compatible
                routes: []
                """);
        RoutingPolicyRepository repository = new RoutingPolicyRepository(path, cfg -> STUB_ADAPTER);
        assertThat(repository.adapterFor("good")).isPresent();

        Files.writeString(path, """
                providers:
                  good:
                    baseUrl: https://8.8.8.8
                    type: openai-compatible
                  evil:
                    baseUrl: http://127.0.0.1:8080
                    type: openai-compatible
                routes: []
                """);

        assertThatThrownBy(repository::reload).isInstanceOf(SsrfProtectionException.class);
        assertThat(repository.adapterFor("good")).isPresent();
        assertThat(repository.adapterFor("evil")).isEmpty();
    }
}
