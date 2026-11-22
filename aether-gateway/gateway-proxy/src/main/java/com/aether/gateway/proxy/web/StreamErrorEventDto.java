package com.aether.gateway.proxy.web;

/** The final SSE data event of a stream the provider failed mid-way, in OpenAI's error shape. */
public record StreamErrorEventDto(Error error) {

    public record Error(String message, String type, int code) {
    }
}
