package com.aether.gateway.proxy.config;

import com.aether.gateway.core.port.ChatCompletionUseCase;
import com.aether.gateway.core.port.ChatStreamUseCase;
import com.aether.gateway.core.port.ModelCatalogUseCase;
import com.aether.gateway.router.ChatCompletionOrchestrator;
import com.aether.gateway.router.ChatStreamOrchestrator;
import com.aether.gateway.router.MockProviderForwarder;
import com.aether.gateway.router.MockProviderModelCatalog;
import com.aether.gateway.router.MockProviderStreamForwarder;
import com.aether.gateway.router.ProviderCaller;
import com.aether.gateway.router.StreamingProviderCaller;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.Executors;

@Configuration
public class GatewayConfig {

    @Bean
    public Scheduler virtualThreadScheduler() {
        return Schedulers.fromExecutor(Executors.newVirtualThreadPerTaskExecutor());
    }

    @Bean
    public ProviderCaller providerCaller(
            @Value("${aether.mock-provider.base-url}") String mockProviderBaseUrl) {
        return new MockProviderForwarder(RestClient.builder(), mockProviderBaseUrl);
    }

    @Bean
    public ChatCompletionUseCase chatCompletionUseCase(ProviderCaller providerCaller) {
        return new ChatCompletionOrchestrator(providerCaller);
    }

    @Bean
    public StreamingProviderCaller streamingProviderCaller(
            @Value("${aether.mock-provider.base-url}") String mockProviderBaseUrl) {
        return new MockProviderStreamForwarder(WebClient.builder(), mockProviderBaseUrl);
    }

    @Bean
    public ChatStreamUseCase chatStreamUseCase(StreamingProviderCaller streamingProviderCaller) {
        return new ChatStreamOrchestrator(streamingProviderCaller);
    }

    @Bean
    public ModelCatalogUseCase modelCatalogUseCase(
            @Value("${aether.mock-provider.base-url}") String mockProviderBaseUrl) {
        return new MockProviderModelCatalog(RestClient.builder(), mockProviderBaseUrl);
    }
}
