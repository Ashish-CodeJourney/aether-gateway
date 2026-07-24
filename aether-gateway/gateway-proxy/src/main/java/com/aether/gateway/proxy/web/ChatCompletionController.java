package com.aether.gateway.proxy.web;

import com.aether.gateway.core.domain.ApiKeyContext;
import com.aether.gateway.core.domain.ChatCompletionRequest;
import com.aether.gateway.core.domain.ProviderResponse;
import com.aether.gateway.core.domain.QuotaDecision;
import com.aether.gateway.core.domain.TokenEstimator;
import com.aether.gateway.core.port.ApiKeyLookupPort;
import com.aether.gateway.core.port.ChatCompletionUseCase;
import com.aether.gateway.core.port.ChatStreamUseCase;
import com.aether.gateway.core.port.QuotaPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.util.UUID;
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
    private static final String QUOTA_REMAINING_HEADER = "X-Aether-Quota-Remaining";

    private final ChatCompletionUseCase chatCompletionUseCase;
    private final ChatStreamUseCase chatStreamUseCase;
    private final ApiKeyLookupPort apiKeyLookupPort;
    private final QuotaPort quotaPort;
    private final TokenEstimator tokenEstimator;
    private final ChatCompletionDtoMapper mapper;
    private final Scheduler virtualThreadScheduler;

    public ChatCompletionController(
            ChatCompletionUseCase chatCompletionUseCase,
            ChatStreamUseCase chatStreamUseCase,
            ApiKeyLookupPort apiKeyLookupPort,
            QuotaPort quotaPort,
            TokenEstimator tokenEstimator,
            ChatCompletionDtoMapper mapper,
            Scheduler virtualThreadScheduler) {
        this.chatCompletionUseCase = chatCompletionUseCase;
        this.chatStreamUseCase = chatStreamUseCase;
        this.apiKeyLookupPort = apiKeyLookupPort;
        this.quotaPort = quotaPort;
        this.tokenEstimator = tokenEstimator;
        this.mapper = mapper;
        this.virtualThreadScheduler = virtualThreadScheduler;
    }

    /**
     * F5: quota enforcement only applies to requests that present a
     * recognized API key. Mandatory authentication on every request is
     * F9 (security hardening), not in this phase's scope (Phase 06 task
     * list only calls for "read access" to key limits); a request with
     * no {@code Authorization} header is treated as unmetered rather
     * than rejected.
     */
    @PostMapping(value = "/v1/chat/completions")
    public Mono<ResponseEntity<Object>> complete(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody ChatCompletionRequestDto requestDto) {
        ChatCompletionRequest domainRequest = mapper.toDomain(requestDto);
        return Mono.fromCallable(() -> checkQuota(authorization, domainRequest))
                .subscribeOn(virtualThreadScheduler)
                .flatMap(outcome -> dispatch(outcome, domainRequest));
    }

    private sealed interface QuotaOutcome {
        record Unmetered() implements QuotaOutcome {
        }

        record Proceed(ApiKeyContext key, QuotaDecision.Allowed allowed, long estimatedTokens) implements QuotaOutcome {
        }

        record Rejected(QuotaDecision.Rejected rejected) implements QuotaOutcome {
        }
    }

    private QuotaOutcome checkQuota(String authorization, ChatCompletionRequest domainRequest) {
        if (authorization == null || authorization.isBlank()) {
            return new QuotaOutcome.Unmetered();
        }
        String rawKey = authorization.startsWith("Bearer ") ? authorization.substring(7) : authorization;
        ApiKeyContext key = apiKeyLookupPort.findByRawKey(rawKey).orElse(null);
        if (key == null) {
            return new QuotaOutcome.Unmetered();
        }

        long estimatedTokens = tokenEstimator.estimatePessimisticTotal(domainRequest);
        String requestId = UUID.randomUUID().toString();
        QuotaDecision decision = quotaPort.checkAndReserve(key, requestId, estimatedTokens);
        return switch (decision) {
            case QuotaDecision.Allowed allowed -> new QuotaOutcome.Proceed(key, allowed, estimatedTokens);
            case QuotaDecision.Rejected rejected -> new QuotaOutcome.Rejected(rejected);
        };
    }

    private Mono<ResponseEntity<Object>> dispatch(QuotaOutcome outcome, ChatCompletionRequest domainRequest) {
        return switch (outcome) {
            case QuotaOutcome.Unmetered ignored ->
                    domainRequest.stream() ? streamingResponse(domainRequest, null, null, 0)
                            : nonStreamingResponse(domainRequest, null, null);
            case QuotaOutcome.Rejected rejected -> Mono.just(quotaRejectedResponse(rejected.rejected()));
            case QuotaOutcome.Proceed proceed ->
                    domainRequest.stream()
                            ? streamingResponse(domainRequest, proceed.key(), proceed.allowed(), proceed.estimatedTokens())
                            : nonStreamingResponse(domainRequest, proceed.key(), proceed.allowed());
        };
    }

    private ResponseEntity<Object> quotaRejectedResponse(QuotaDecision.Rejected rejected) {
        var body = new ErrorEnvelopeDto(
                "https://aether.dev/problems/quota_exceeded",
                rejected.reason(),
                HttpStatus.TOO_MANY_REQUESTS.value(),
                "Quota exceeded: " + rejected.reason(),
                "/v1/chat/completions");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(QUOTA_REMAINING_HEADER, String.valueOf(rejected.remainingMonthlyTokens()))
                .body((Object) body);
    }

    private Mono<ResponseEntity<Object>> nonStreamingResponse(
            ChatCompletionRequest domainRequest, ApiKeyContext key, QuotaDecision.Allowed allowed) {
        return Mono.fromCallable(() -> chatCompletionUseCase.complete(domainRequest))
                .subscribeOn(virtualThreadScheduler)
                .doOnNext(response -> reconcileAndRelease(key, allowed, response))
                .doOnError(error -> reconcileAndReleaseOnFailure(key, allowed))
                .map(response -> toResponseEntity(response, allowed));
    }

    private void reconcileAndRelease(ApiKeyContext key, QuotaDecision.Allowed allowed, ProviderResponse response) {
        if (key == null || allowed == null) {
            return;
        }
        long actualTokens = response instanceof ProviderResponse.Completion completion
                ? completion.response().usage().totalTokens()
                : 0;
        quotaPort.reconcile(key.keyId(), allowed.requestId(), actualTokens);
        quotaPort.releaseConcurrencySlot(key.keyId(), allowed.requestId());
    }

    private void reconcileAndReleaseOnFailure(ApiKeyContext key, QuotaDecision.Allowed allowed) {
        if (key == null || allowed == null) {
            return;
        }
        quotaPort.reconcile(key.keyId(), allowed.requestId(), 0);
        quotaPort.releaseConcurrencySlot(key.keyId(), allowed.requestId());
    }

    private void releaseStreamQuota(ApiKeyContext key, QuotaDecision.Allowed allowed, long estimatedTokens) {
        if (key == null || allowed == null) {
            return;
        }
        quotaPort.reconcile(key.keyId(), allowed.requestId(), estimatedTokens);
        quotaPort.releaseConcurrencySlot(key.keyId(), allowed.requestId());
    }

    private ResponseEntity<Object> toResponseEntity(ProviderResponse providerResponse, QuotaDecision.Allowed allowed) {
        return switch (providerResponse) {
            case ProviderResponse.Completion completion ->
                    quotaHeader(ResponseEntity.ok(), allowed).body((Object) mapper.toDto(completion.response()));
            case ProviderResponse.ProviderError error -> {
                var status = HttpStatus.valueOf(error.httpStatus());
                var body = new ErrorEnvelopeDto(
                        "https://aether.dev/problems/" + error.errorCode(),
                        error.errorCode(),
                        error.httpStatus(),
                        error.message(),
                        "/v1/chat/completions");
                yield quotaHeader(ResponseEntity.status(status), allowed).body((Object) body);
            }
            case ProviderResponse.StreamChunk chunk ->
                    throw new IllegalStateException("Unexpected stream chunk on the non-streaming response path");
        };
    }

    private ResponseEntity.BodyBuilder quotaHeader(ResponseEntity.BodyBuilder builder, QuotaDecision.Allowed allowed) {
        if (allowed != null) {
            builder.header(QUOTA_REMAINING_HEADER, String.valueOf(allowed.remainingMonthlyTokens()));
        }
        return builder;
    }

    private Mono<ResponseEntity<Object>> streamingResponse(
            ChatCompletionRequest domainRequest, ApiKeyContext key, QuotaDecision.Allowed allowed, long estimatedTokens) {
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
                        domainRequest.model(), chunksSent.get()))
                // Streaming responses don't surface actual token usage at
                // this layer yet (Phase 08); reconcile with the original
                // estimate (a no-op adjustment) so the reservation isn't
                // left dangling until its 5-minute TTL expires, and always
                // free the concurrency slot regardless of how the stream
                // ended (completed, errored, or cancelled by the client).
                .doFinally(signal -> releaseStreamQuota(key, allowed, estimatedTokens));

        ResponseEntity.BodyBuilder builder = quotaHeader(ResponseEntity.ok(), allowed)
                .contentType(MediaType.TEXT_EVENT_STREAM);
        return Mono.just(builder.body((Object) body));
    }

    private ServerSentEvent<String> toSseEvent(ProviderResponse.StreamChunk chunk) {
        return ServerSentEvent.builder(mapper.toJson(mapper.toDto(chunk))).build();
    }
}
