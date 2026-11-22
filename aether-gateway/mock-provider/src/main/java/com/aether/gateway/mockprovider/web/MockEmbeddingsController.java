package com.aether.gateway.mockprovider.web;

import com.aether.gateway.mockprovider.DefaultControlsHolder;
import com.aether.gateway.mockprovider.DeterministicEmbeddingGenerator;
import com.aether.gateway.mockprovider.FaultInjector;
import com.aether.gateway.mockprovider.MockControls;
import com.aether.gateway.mockprovider.MockFailure;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;

/**
 * OpenAI-shaped {@code POST /v1/embeddings} with deterministic vectors.
 * Honours the same fault controls as the chat endpoint ({@code X-Mock-Fail},
 * {@code X-Mock-Latency}, and the {@code /_mock/config} defaults), so the
 * gateway's failover can be exercised for embeddings exactly as for chat.
 */
@RestController
public class MockEmbeddingsController {

    private final FaultInjector faultInjector = new FaultInjector(new Random());
    private final DeterministicEmbeddingGenerator generator = new DeterministicEmbeddingGenerator();
    private final DefaultControlsHolder defaultControlsHolder;

    public MockEmbeddingsController(DefaultControlsHolder defaultControlsHolder) {
        this.defaultControlsHolder = defaultControlsHolder;
    }

    @PostMapping("/v1/embeddings")
    public Mono<ResponseEntity<Object>> embed(@RequestBody EmbeddingRequestDto request, @RequestHeader HttpHeaders headers) {
        MockControls controls = defaultControlsHolder.resolve(new MockControls(
                parseLong(headers.getFirst("X-Mock-Latency")), headers.getFirst("X-Mock-Fail"),
                parseDouble(headers.getFirst("X-Mock-Fail-Rate")), null, null, null));
        var injectedFailure = faultInjector.determineFailure(controls);
        ResponseEntity<Object> response = injectedFailure
                .map(MockEmbeddingsController::failure)
                .orElseGet(() -> ResponseEntity.ok((Object) success(request)));
        long delay = (controls.latencyMs() != null ? controls.latencyMs() : 0)
                + injectedFailure.map(MockFailure::artificialDelayMs).orElse(0L);
        return delay > 0 ? Mono.delay(Duration.ofMillis(delay)).thenReturn(response) : Mono.just(response);
    }

    private EmbeddingResponseDto success(EmbeddingRequestDto request) {
        List<String> inputs = inputsOf(request.input());
        List<EmbeddingResponseDto.Item> data = IntStream.range(0, inputs.size())
                .mapToObj(i -> new EmbeddingResponseDto.Item("embedding", i, generator.embed(inputs.get(i), request.dimensions())))
                .toList();
        int promptTokens = inputs.stream().mapToInt(text -> Math.max(1, text.length() / 4)).sum();
        return new EmbeddingResponseDto("list", data, request.model(), new EmbeddingResponseDto.Usage(promptTokens, promptTokens));
    }

    private static List<String> inputsOf(Object input) {
        if (input instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of(String.valueOf(input));
    }

    private static ResponseEntity<Object> failure(MockFailure failure) {
        var error = new ErrorEnvelopeDto(
                "https://mock-provider.local/problems/" + failure.errorCode(),
                failure.errorCode(),
                failure.httpStatus(),
                "Injected failure via X-Mock-Fail: " + failure.errorCode(),
                "/v1/embeddings");
        return ResponseEntity.status(failure.httpStatus()).body(error);
    }

    private static Long parseLong(String value) {
        return value == null ? null : Long.parseLong(value);
    }

    private static Double parseDouble(String value) {
        return value == null ? null : Double.parseDouble(value);
    }
}
