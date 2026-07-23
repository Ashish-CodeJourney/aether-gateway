package com.aether.gateway.mockprovider.web;

import com.aether.gateway.mockprovider.DefaultControlsHolder;
import com.aether.gateway.mockprovider.FaultInjector;
import com.aether.gateway.mockprovider.MockControls;
import com.aether.gateway.mockprovider.MockFailure;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.UUID;

@RestController
public class MockChatController {

    private final FaultInjector faultInjector = new FaultInjector(new Random());
    private final DefaultControlsHolder defaultControlsHolder;

    public MockChatController(DefaultControlsHolder defaultControlsHolder) {
        this.defaultControlsHolder = defaultControlsHolder;
    }

    @PostMapping(value = "/v1/chat/completions", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Object>> complete(
            @RequestBody ChatCompletionRequestDto request,
            @RequestHeader HttpHeaders headers) {

        MockControls controls = defaultControlsHolder.resolve(parseControls(headers));
        Mono<ResponseEntity<Object>> result = buildResponse(request, controls);

        if (controls.latencyMs() != null) {
            return Mono.delay(Duration.ofMillis(controls.latencyMs())).then(result);
        }
        return result;
    }

    private Mono<ResponseEntity<Object>> buildResponse(ChatCompletionRequestDto request, MockControls controls) {
        var failure = faultInjector.determineFailure(controls);
        if (failure.isPresent()) {
            return failureResponse(failure.get());
        }
        return Mono.just(ResponseEntity.ok(successBody(request, controls)));
    }

    private Mono<ResponseEntity<Object>> failureResponse(MockFailure failure) {
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
        if (failure.artificialDelayMs() > 0) {
            return Mono.delay(Duration.ofMillis(failure.artificialDelayMs())).then(response);
        }
        return response;
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
