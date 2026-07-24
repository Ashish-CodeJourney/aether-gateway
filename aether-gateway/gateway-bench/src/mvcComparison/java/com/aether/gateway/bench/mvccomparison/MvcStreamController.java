package com.aether.gateway.bench.mvccomparison;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Blocking SSE relay: reads the upstream mock-provider's chunked
 * response byte-by-byte on the calling (virtual) thread and writes it
 * straight to the servlet response, with no reactive operators
 * anywhere. This is the MVC+virtual-threads side of experiment 5's
 * comparison against gateway-proxy's real WebFlux streaming path.
 */
@RestController
public class MvcStreamController {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final String upstreamUrl;

    public MvcStreamController(@Value("${aether.bench.upstream-url}") String upstreamUrl) {
        this.upstreamUrl = upstreamUrl;
    }

    @PostMapping(value = "/v1/chat/completions", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public void stream(@RequestBody String requestBody, HttpServletResponse response) throws IOException {
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setCharacterEncoding("UTF-8");

        HttpRequest upstreamRequest = HttpRequest.newBuilder()
                .uri(URI.create(upstreamUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<InputStream> upstreamResponse;
        try {
            upstreamResponse = httpClient.send(upstreamRequest, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while calling the upstream provider", e);
        }

        try (InputStream upstreamBody = upstreamResponse.body(); OutputStream out = response.getOutputStream()) {
            byte[] buffer = new byte[512];
            int bytesRead;
            while ((bytesRead = upstreamBody.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
                out.flush();
            }
        }
    }
}
