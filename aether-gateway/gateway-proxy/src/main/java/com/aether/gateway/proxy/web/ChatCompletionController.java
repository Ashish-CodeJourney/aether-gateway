package com.aether.gateway.proxy.web;

import com.aether.gateway.core.domain.ChatCompletionRequest;
import com.aether.gateway.core.domain.ProviderResponse;
import com.aether.gateway.core.port.ChatCompletionUseCase;
import com.aether.gateway.core.port.ChatStreamUseCase;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * ADR-002 addendum: the non-streaming path is business-logic-simple
 * (blocking JDBC/HTTP calls under the hood, from Phase 05 onward), but
 * still runs on the single WebFlux/Netty server. The blocking use-case
 * call is dispatched onto a virtual-thread scheduler so it never blocks
 * an event-loop thread. The streaming path (Phase 04/M1) stays on native
 * Flux end to end so client-disconnect cancellation propagates all the
 * way to the upstream provider call automatically (F1.4). The controller
 * itself stays thin either way, per the hexagonal boundary check (parse,
 * delegate, map, nothing else).
 *
 * <p>Both branches return {@code Mono<ResponseEntity<Object>>}: for the
 * non-streaming branch the body is a plain DTO, for the streaming branch
 * the body is a {@code Flux<ServerSentEvent<String>>}, resolved by
 * Spring's {@code ResponseEntityResultHandler} at the actual runtime
 * type. (An earlier attempt using {@code Mono<ServerResponse>} on this
 * {@code @RestController} failed: WebFlux's default result-handler chain
 * does not register a handler for that combination without a
 * RouterFunction bean present, and falls through to view resolution,
 * throwing "Could not resolve view with name ...". ResponseEntity is the
 * well-supported path for annotated controllers.)
 */
@RestController
public class ChatCompletionController {

    private static final Logger log = LoggerFactory.getLogger(ChatCompletionController.class);

    private final ChatCompletionUseCase chatCompletionUseCase;
    private final ChatStreamUseCase chatStreamUseCase;
    private final ChatCompletionDtoMapper mapper;
    private final Scheduler virtualThreadScheduler;

    public ChatCompletionController(
            ChatCompletionUseCase chatCompletionUseCase,
            ChatStreamUseCase chatStreamUseCase,
            ChatCompletionDtoMapper mapper,
            Scheduler virtualThreadScheduler) {
        this.chatCompletionUseCase = chatCompletionUseCase;
        this.chatStreamUseCase = chatStreamUseCase;
        this.mapper = mapper;
        this.virtualThreadScheduler = virtualThreadScheduler;
    }

    @PostMapping(value = "/v1/chat/completions")
    public Mono<ResponseEntity<Object>> complete(@RequestBody ChatCompletionRequestDto requestDto) {
        ChatCompletionRequest domainRequest = mapper.toDomain(requestDto);
        return domainRequest.stream() ? streamingResponse(domainRequest) : nonStreamingResponse(domainRequest);
    }

    private Mono<ResponseEntity<Object>> nonStreamingResponse(ChatCompletionRequest domainRequest) {
        return Mono.fromCallable(() -> chatCompletionUseCase.complete(domainRequest))
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
                    throw new IllegalStateException("Unexpected stream chunk on the non-streaming response path");
        };
    }

    private Mono<ResponseEntity<Object>> streamingResponse(ChatCompletionRequest domainRequest) {
        // F1.5: capture partial usage (here, chunk count as a token-count
        // proxy) when a stream is cancelled mid-way. In-process logging is
        // sufficient at this phase; persistence into the request log
        // arrives with Phase 08 (M5).
        AtomicInteger chunksSent = new AtomicInteger(0);

        Flux<ServerSentEvent<String>> body = JdkFlowAdapter
                .flowPublisherToFlux(chatStreamUseCase.stream(domainRequest))
                .doOnNext(chunk -> chunksSent.incrementAndGet())
                .map(this::toSseEvent)
                .concatWith(Mono.just(ServerSentEvent.<String>builder("[DONE]").build()))
                .doOnCancel(() -> log.info(
                        "Stream for model {} cancelled by client after {} chunks",
                        domainRequest.model(), chunksSent.get()));

        return Mono.just(ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body((Object) body));
    }

    private ServerSentEvent<String> toSseEvent(ProviderResponse.StreamChunk chunk) {
        return ServerSentEvent.builder(mapper.toJson(mapper.toDto(chunk))).build();
    }
}
