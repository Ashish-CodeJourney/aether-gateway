package com.aether.gateway.acceptance.support;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** Thin HTTP wrapper; every acceptance step goes through this, never through gateway internals. */
public class GatewayClient {

    private final HttpClient httpClient = HttpClient.newHttpClient();

    public HttpResponse<String> postJson(String url, String jsonBody) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    public HttpResponse<String> postJson(String url, String jsonBody, String bearerApiKey) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + bearerApiKey)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** {@code bearerApiKey} and any entry in {@code extraHeaders} may be null/empty to omit them. */
    public HttpResponse<String> postJson(String url, String jsonBody, String bearerApiKey, Map<String, String> extraHeaders) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json");
        if (bearerApiKey != null) {
            builder.header("Authorization", "Bearer " + bearerApiKey);
        }
        if (extraHeaders != null) {
            extraHeaders.forEach(builder::header);
        }
        HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(jsonBody)).build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    public HttpResponse<String> putJson(String url, String jsonBody) throws Exception {
        return putJson(url, jsonBody, null);
    }

    public HttpResponse<String> putJson(String url, String jsonBody, Map<String, String> extraHeaders) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json");
        if (extraHeaders != null) {
            extraHeaders.forEach(builder::header);
        }
        HttpRequest request = builder.PUT(HttpRequest.BodyPublishers.ofString(jsonBody)).build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    public HttpResponse<String> get(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
