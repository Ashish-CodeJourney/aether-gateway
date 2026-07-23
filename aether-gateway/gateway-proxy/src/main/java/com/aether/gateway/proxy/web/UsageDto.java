package com.aether.gateway.proxy.web;

public record UsageDto(int promptTokens, int completionTokens, int totalTokens) {
}
