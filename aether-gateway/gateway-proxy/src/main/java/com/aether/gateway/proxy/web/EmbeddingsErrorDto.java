package com.aether.gateway.proxy.web;

/**
 * The gateway's usual RFC 9457 fields plus OpenAI's {@code error} object,
 * so both existing gateway clients (reading {@code title}) and OpenAI SDKs
 * (reading {@code error.message}) get a meaningful error from the one body.
 */
public record EmbeddingsErrorDto(String type, String title, int status, String detail, String instance, OpenAiError error) {

    public record OpenAiError(String message, String type, String param, String code) {
    }

    public static EmbeddingsErrorDto of(int status, String code, String message, String param) {
        return new EmbeddingsErrorDto(
                "https://aether.dev/problems/" + code, code, status, message, "/v1/embeddings",
                new OpenAiError(message, openAiType(status), param, code));
    }

    private static String openAiType(int status) {
        if (status == 401) {
            return "authentication_error";
        }
        if (status == 429) {
            return "rate_limit_error";
        }
        return status < 500 ? "invalid_request_error" : "api_error";
    }
}
