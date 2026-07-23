package com.aether.gateway.mockprovider.web;

/** RFC 9457-shaped error body, matching docs/design/api-contract.md. */
public record ErrorEnvelopeDto(String type, String title, int status, String detail, String instance) {
}
