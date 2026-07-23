package com.aether.gateway.mockprovider.web;

import com.aether.gateway.mockprovider.ActiveStreamTracker;
import com.aether.gateway.mockprovider.DefaultControlsHolder;
import com.aether.gateway.mockprovider.FaultInjector;
import com.aether.gateway.mockprovider.MockControls;
import com.aether.gateway.mockprovider.MockFailure;
import com.aether.gateway.mockprovider.StreamChunkGenerator;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.UUID;

// Uses Mono<ResponseEntity<Object>> for both the JSON and SSE response
// shapes; see ChatCompletionController's javadoc in gateway-proxy for
// why Mono<ServerResponse> does not work on an annotated controller
// without a RouterFunction bean present.
@RestController
public class MockChatController {

    private final FaultInjector faultInjector = new FaultInjector(new Random());
    private final StreamChunkGenerator streamChunkGenerator = new StreamChunkGenerator();
    private final DefaultControlsHolder defaultControlsHolder;
    private final ActiveStreamTracker activeStreamTracker;
    private final ObjectMapper jsonMapper = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build();

    public MockChatController(DefaultControlsHolder defaultControlsHolder, ActiveStreamTracker activeStreamTracker) {
        this.defaultControlsHolder = defaultControlsHolder;
        this.activeStreamTracker = activeStreamTracker;
    }

    @PostMapping(value = "/v1/chat/completions")
    public Mono<ResponseEntity<Object>> complete(
            @RequestBody ChatCompletionRequestDto request,
            @RequestHeader HttpHeaders headers) {

        MockControls controls = defaultControlsHolder.resolve(parseControls(headers));
        boolean streaming = Boolean.TRUE.equals(request.stream());

        var failure = faultInjector.determineFailure(controls);
        if (failure.isPresent()) {
            return failureResponse(failure.get(), controls.latencyMs());
        }

        return streaming ? streamingResponse(request, controls) : nonStreamingResponse(request, controls);
    }

    private Mono<ResponseEntity<Object>> nonStreamingResponse(ChatCompletionRequestDto request, MockControls controls) {
        Mono<ResponseEntity<Object>> response = Mono.just(
                ResponseEntity.ok((Object) successBody(request, controls)));
        return applyLatency(response, controls.latencyMs());
    }

    private Mono<ResponseEntity<Object>> streamingResponse(ChatCompletionRequestDto request, MockControls controls) {
        String responseId = "mock-" + UUID.randomUUID();
        List<StreamChunkGenerator.Chunk> chunks = streamChunkGenerator.generate(
                responseId, request.model(), "Mock streaming response for model " + request.model());

        int limit = controls.truncateAt() != null ? Math.min(controls.truncateAt(), chunks.size()) : chunks.size();
        boolean truncated = controls.truncateAt() != null && controls.truncateAt() < chunks.size();

        Flux<ServerSentEvent<String>> body = Flux.fromIterable(chunks.subList(0, limit))
                .map(this::toSseEvent);

        if (controls.streamDelayMs() != null) {
            body = body.delayElements(Duration.ofMillis(controls.streamDelayMs()));
        }
        if (!truncated) {
            body = body.concatWith(Mono.just(ServerSentEvent.<String>builder("[DONE]").build()));
        }

        body = body
                .doOnSubscribe(s -> activeStreamTracker.streamStarted())
                .doFinally(signal -> activeStreamTracker.streamFinished());

        Mono<ResponseEntity<Object>> response = Mono.just(ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body((Object) body));
        return applyLatency(response, controls.latencyMs());
    }

    private ServerSentEvent<String> toSseEvent(StreamChunkGenerator.Chunk chunk) {
        var delta = new DeltaDto(chunk.deltaContent());
        var choice = new StreamChoiceDto(0, delta, chunk.finishReason());
        var payload = new StreamChunkResponseDto(
                chunk.id(), "chat.completion.chunk", Instant.now().getEpochSecond(),
                chunk.model(), List.of(choice));
        return ServerSentEvent.builder(writeJson(payload)).build();
    }

    private String writeJson(Object value) {
        try {
            return jsonMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialise mock stream chunk", e);
        }
    }

    private Mono<ResponseEntity<Object>> applyLatency(Mono<ResponseEntity<Object>> response, Long latencyMs) {
        return latencyMs != null ? Mono.delay(Duration.ofMillis(latencyMs)).then(response) : response;
    }

    private Mono<ResponseEntity<Object>> failureResponse(MockFailure failure, Long latencyMs) {
        Mono<ResponseEntity<Object>> response;
        if (failure.malformedBody()) {
            response = Mono.just(ResponseEntity.status(failure.httpStatus())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body((Object) "{ \"this is not valid json, deliberately truncated"));
        } else {
            var error = new ErrorEnvelopeDto(
                    "https://mock-provider.local/problems/" + failure.errorCode(),
                    failure.errorCode(),
                    failure.httpStatus(),
                    "Injected failure via X-Mock-Fail: " + failure.errorCode(),
                    "/v1/chat/completions");
            response = Mono.just(ResponseEntity.status(failure.httpStatus()).body((Object) error));
        }
        long totalDelay = failure.artificialDelayMs() + (latencyMs != null ? latencyMs : 0);
        return totalDelay > 0 ? Mono.delay(Duration.ofMillis(totalDelay)).then(response) : response;
    }

    private ChatCompletionResponseDto successBody(ChatCompletionRequestDto request, MockControls controls) {
        int completionTokens = controls.tokens() != null ? controls.tokens() : 12;
        int promptTokens = estimatePromptTokens(request);

        var message = new ChatMessageDto("assistant", "Mock response for model " + request.model());
        var choice = new ChoiceDto(0, message, "stop");
        var usage = new UsageDto(promptTokens, completionTokens, promptTokens + completionTokens);

        return new ChatCompletionResponseDto(
                "mock-" + UUID.randomUUID(),
                "chat.completion",
                Instant.now().getEpochSecond(),
                request.model(),
                List.of(choice),
                usage);
    }

    private int estimatePromptTokens(ChatCompletionRequestDto request) {
        return request.messages().stream()
                .mapToInt(m -> Math.max(1, m.content().length() / 4))
                .sum();
    }

    private MockControls parseControls(HttpHeaders headers) {
        Long latency = parseLong(headers.getFirst("X-Mock-Latency"));
        String failMode = headers.getFirst("X-Mock-Fail");
        Double failRate = parseDouble(headers.getFirst("X-Mock-Fail-Rate"));
        Integer streamDelay = parseInt(headers.getFirst("X-Mock-Stream-Delay"));
        Integer truncateAt = parseInt(headers.getFirst("X-Mock-Truncate-At"));
        Integer tokens = parseInt(headers.getFirst("X-Mock-Tokens"));
        return new MockControls(latency, failMode, failRate, streamDelay, truncateAt, tokens);
    }

    private static Long parseLong(String value) {
        return value == null ? null : Long.parseLong(value);
    }

    private static Integer parseInt(String value) {
        return value == null ? null : Integer.parseInt(value);
    }

    private static Double parseDouble(String value) {
        return value == null ? null : Double.parseDouble(value);
    }
}
