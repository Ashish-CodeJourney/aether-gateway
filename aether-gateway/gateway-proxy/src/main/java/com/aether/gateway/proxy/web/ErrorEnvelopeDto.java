package com.aether.gateway.proxy.web;

/** RFC 9457-shaped error body, per docs/design/api-contract.md. */
public record ErrorEnvelopeDto(String type, String title, int status, String detail, String instance) {
}
