package com.aether.gateway.proxy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// F6.1: @EnableScheduling powers the breaker-state gauge poller (see
// GatewayConfig/BreakerMetricsPoller) - periodic, not push-per-transition,
// since ResilientRouter's breaker registry has no transition-listener hook.
@SpringBootApplication(scanBasePackages = "com.aether.gateway")
@EnableScheduling
public class AetherGatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(AetherGatewayApplication.class, args);
    }
}
