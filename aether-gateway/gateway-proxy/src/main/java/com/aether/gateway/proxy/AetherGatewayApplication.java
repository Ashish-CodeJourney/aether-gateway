package com.aether.gateway.proxy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.aether.gateway")
public class AetherGatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(AetherGatewayApplication.class, args);
    }
}
