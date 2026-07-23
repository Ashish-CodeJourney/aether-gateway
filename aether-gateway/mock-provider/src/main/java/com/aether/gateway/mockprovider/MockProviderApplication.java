package com.aether.gateway.mockprovider;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class MockProviderApplication {
    public static void main(String[] args) {
        SpringApplication.run(MockProviderApplication.class, args);
    }

    @Bean
    public DefaultControlsHolder defaultControlsHolder() {
        return new DefaultControlsHolder();
    }

    @Bean
    public ActiveStreamTracker activeStreamTracker() {
        return new ActiveStreamTracker();
    }
}
