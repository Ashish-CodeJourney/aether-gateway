package com.aether.gateway.admin.web;

import java.util.List;

public record CreateApiKeyRequestDto(String name, List<String> tags, Integer rpsLimit, Integer concurrencyLimit, Long monthlyTokenBudget) {
}
