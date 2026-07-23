package com.aether.gateway.proxy.config;

import com.aether.gateway.core.port.ChatCompletionUseCase;
import com.aether.gateway.router.ChatCompletionOrchestrator;
import com.aether.gateway.router.MockProviderForwarder;
import com.aether.gateway.router.ProviderCaller;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
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
}
