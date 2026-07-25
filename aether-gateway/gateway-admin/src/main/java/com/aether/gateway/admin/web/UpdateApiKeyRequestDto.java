package com.aether.gateway.admin.web;

public record UpdateApiKeyRequestDto(Boolean enabled, Integer rpsLimit, Integer concurrencyLimit, Long monthlyTokenBudget) {
}
