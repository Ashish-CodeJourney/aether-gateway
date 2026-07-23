package com.aether.gateway.core.domain;

/**
 * RFC 9457 problem-details shape, per docs/design/api-contract.md.
 */
public record GatewayError(String type, String title, int status, String detail, String instance) {
}
