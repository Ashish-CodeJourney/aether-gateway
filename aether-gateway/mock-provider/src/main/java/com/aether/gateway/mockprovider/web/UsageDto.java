package com.aether.gateway.mockprovider.web;

public record UsageDto(int promptTokens, int completionTokens, int totalTokens) {
}
