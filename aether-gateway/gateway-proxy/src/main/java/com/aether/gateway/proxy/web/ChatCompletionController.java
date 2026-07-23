package com.aether.gateway.proxy.web;

import com.aether.gateway.core.domain.ProviderResponse;
import com.aether.gateway.core.port.ChatCompletionUseCase;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

/**
 * ADR-002 addendum: the non-streaming path is business-logic-simple
 * (blocking JDBC/HTTP calls under the hood, from Phase 05 onward), but
 * still runs on the single WebFlux/Netty server. The blocking use-case
 * call is dispatched onto a virtual-thread scheduler so it never blocks
 * an event-loop thread; the controller itself stays thin, per the
 * hexagonal boundary check (parse, delegate, map, nothing else).
 */
@RestController
public class ChatCompletionController {

    private final ChatCompletionUseCase chatCompletionUseCase;
    private final ChatCompletionDtoMapper mapper;
    private final Scheduler virtualThreadScheduler;

    public ChatCompletionController(
            ChatCompletionUseCase chatCompletionUseCase,
            ChatCompletionDtoMapper mapper,
            Scheduler virtualThreadScheduler) {
        this.chatCompletionUseCase = chatCompletionUseCase;
        this.mapper = mapper;
        this.virtualThreadScheduler = virtualThreadScheduler;
    }

    @PostMapping(value = "/v1/chat/completions", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Object>> complete(@RequestBody ChatCompletionRequestDto requestDto) {
        return Mono.fromCallable(() -> chatCompletionUseCase.complete(mapper.toDomain(requestDto)))
                .subscribeOn(virtualThreadScheduler)
                .map(this::toResponseEntity);
    }

    private ResponseEntity<Object> toResponseEntity(ProviderResponse providerResponse) {
        return switch (providerResponse) {
            case ProviderResponse.Completion completion ->
                    ResponseEntity.ok((Object) mapper.toDto(completion.response()));
            case ProviderResponse.ProviderError error -> {
                var status = HttpStatus.valueOf(error.httpStatus());
                var body = new ErrorEnvelopeDto(
                        "https://aether.dev/problems/" + error.errorCode(),
                        error.errorCode(),
                        error.httpStatus(),
                        error.message(),
                        "/v1/chat/completions");
                yield ResponseEntity.status(status).body((Object) body);
            }
            case ProviderResponse.StreamChunk chunk ->
                    throw new IllegalStateException("Streaming is not supported by this endpoint until Phase 04");
        };
    }
}
