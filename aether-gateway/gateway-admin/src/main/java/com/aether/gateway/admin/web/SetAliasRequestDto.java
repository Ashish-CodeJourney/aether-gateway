package com.aether.gateway.admin.web;

/** PRD 13.2: {@code PUT /admin/prompts/{name}/aliases/{alias}}. F7.4: re-pointing this is the rollback operation. */
public record SetAliasRequestDto(int version) {
}
