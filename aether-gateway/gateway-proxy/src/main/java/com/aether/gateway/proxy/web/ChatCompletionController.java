package com.aether.gateway.proxy.web;

import com.aether.gateway.core.domain.ApiKeyContext;
import com.aether.gateway.core.domain.CacheDecision;
import com.aether.gateway.core.domain.ChatCompletionRequest;
import com.aether.gateway.core.domain.ProviderResponse;
import com.aether.gateway.core.domain.QuotaDecision;
import com.aether.gateway.core.domain.RouteCacheConfig;
import com.aether.gateway.core.domain.RouteConfig;
import com.aether.gateway.core.domain.TokenEstimator;
import com.aether.gateway.core.port.ApiKeyLookupPort;
import com.aether.gateway.core.port.CachePort;
import com.aether.gateway.core.port.ChatCompletionUseCase;
import com.aether.gateway.core.port.ChatStreamUseCase;
import com.aether.gateway.core.port.QuotaPort;
import com.aether.gateway.router.routing.RoutingSource;
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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ADR-002 addendum: the non-streaming path is business-logic-simple
 * (blocking JDBC/HTTP/Redis calls under the hood, from Phase 05
 * onward), but still runs on the single WebFlux/Netty server. Blocking
 * use-case, quota, and cache calls are dispatched onto a virtual-thread
 * scheduler so they never block an event-loop thread. The streaming
 * path (Phase 04/M1) stays on native Flux end to end so client-disconnect
 * cancellation propagates all the way to the upstream provider call
 * automatically (F1.4).
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
    private static final String CACHE_HEADER = "X-Aether-Cache";
    private static final String SIMILARITY_HEADER = "X-Aether-Similarity";
    private static final String ANONYMOUS_NAMESPACE = "anonymous";
    private static final Duration STREAM_REPLAY_WORD_DELAY = Duration.ofMillis(25);

    private final ChatCompletionUseCase chatCompletionUseCase;
    private final ChatStreamUseCase chatStreamUseCase;
    private final ApiKeyLookupPort apiKeyLookupPort;
    private final QuotaPort quotaPort;
    private final TokenEstimator tokenEstimator;
    private final CachePort cachePort;
    private final RoutingSource routingSource;
    private final ChatCompletionDtoMapper mapper;
    private final Scheduler virtualThreadScheduler;

    public ChatCompletionController(
            ChatCompletionUseCase chatCompletionUseCase,
            ChatStreamUseCase chatStreamUseCase,
            ApiKeyLookupPort apiKeyLookupPort,
            QuotaPort quotaPort,
            TokenEstimator tokenEstimator,
            CachePort cachePort,
            RoutingSource routingSource,
            ChatCompletionDtoMapper mapper,
            Scheduler virtualThreadScheduler) {
        this.chatCompletionUseCase = chatCompletionUseCase;
        this.chatStreamUseCase = chatStreamUseCase;
        this.apiKeyLookupPort = apiKeyLookupPort;
        this.quotaPort = quotaPort;
        this.tokenEstimator = tokenEstimator;
        this.cachePort = cachePort;
        this.routingSource = routingSource;
        this.mapper = mapper;
        this.virtualThreadScheduler = virtualThreadScheduler;
    }

    /**
     * F5: quota enforcement only applies to requests that present a
     * recognized API key. Mandatory authentication on every request is
     * F9 (security hardening), not in this phase's scope; a request with
     * no {@code Authorization} header is treated as unmetered rather
     * than rejected, and cached under a shared "anonymous" namespace
     * (F4.4's isolation guarantee is about never crossing *distinct*
     * tenants, which an unauthenticated caller isn't one of).
     */
    @PostMapping(value = "/v1/chat/completions")
    public Mono<ResponseEntity<Object>> complete(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Aether-No-Cache", required = false) String noCacheHeader,
            @RequestHeader(value = "X-Aether-Cache-Threshold", required = false) Double thresholdOverride,
            @RequestBody ChatCompletionRequestDto requestDto) {
        ChatCompletionRequest domainRequest = mapper.toDomain(requestDto);
        boolean noCachePresent = noCacheHeader != null;
        return Mono.fromCallable(() -> checkQuota(authorization, domainRequest))
                .subscribeOn(virtualThreadScheduler)
                .flatMap(quotaOutcome -> Mono
                        .fromCallable(() -> checkCache(quotaOutcome, domainRequest, noCachePresent, thresholdOverride))
                        .subscribeOn(virtualThreadScheduler)
                        .flatMap(cacheLookup -> dispatch(quotaOutcome, domainRequest, cacheLookup)));
    }

    private sealed interface QuotaOutcome {
        record Unmetered() implements QuotaOutcome {
        }

        record Proceed(ApiKeyContext key, QuotaDecision.Allowed allowed, long estimatedTokens) implements QuotaOutcome {
        }

        record Rejected(QuotaDecision.Rejected rejected) implements QuotaOutcome {
        }
    }

    private record CacheLookup(String namespace, CacheDecision decision, RouteCacheConfig routeCache) {
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

    /** F4: skipped entirely (and the underlying quota reservation left untouched) when the quota check already rejected the request. */
    private CacheLookup checkCache(
            QuotaOutcome quotaOutcome, ChatCompletionRequest domainRequest, boolean noCachePresent, Double thresholdOverride) {
        if (quotaOutcome instanceof QuotaOutcome.Rejected) {
            return new CacheLookup(ANONYMOUS_NAMESPACE, new CacheDecision.Bypass("quota_rejected"), RouteCacheConfig.DISABLED);
        }
        String namespace = quotaOutcome instanceof QuotaOutcome.Proceed proceed ? proceed.key().keyId() : ANONYMOUS_NAMESPACE;
        RouteCacheConfig routeCache = routingSource.routeFor(domainRequest.model())
                .map(RouteConfig::cache)
                .orElse(RouteCacheConfig.DISABLED);
        if (!routeCache.enabled()) {
            return new CacheLookup(namespace, new CacheDecision.Bypass("route_cache_disabled"), routeCache);
        }
        CacheDecision decision = cachePort.lookup(namespace, domainRequest, thresholdOverride, noCachePresent);
        return new CacheLookup(namespace, decision, routeCache);
    }

    private Mono<ResponseEntity<Object>> dispatch(QuotaOutcome quotaOutcome, ChatCompletionRequest domainRequest, CacheLookup cacheLookup) {
        if (quotaOutcome instanceof QuotaOutcome.Rejected rejected) {
            return Mono.just(quotaRejectedResponse(rejected.rejected()));
        }
        ApiKeyContext key = quotaOutcome instanceof QuotaOutcome.Proceed proceed ? proceed.key() : null;
        QuotaDecision.Allowed allowed = quotaOutcome instanceof QuotaOutcome.Proceed proceed ? proceed.allowed() : null;
        long estimatedTokens = quotaOutcome instanceof QuotaOutcome.Proceed proceed ? proceed.estimatedTokens() : 0;

        if (cacheLookup.decision() instanceof CacheDecision.ExactHit hit) {
            return cacheHitResponse(hit.responseBodyJson(), null, key, allowed, domainRequest.stream());
        }
        if (cacheLookup.decision() instanceof CacheDecision.SemanticHit hit) {
            return cacheHitResponse(hit.responseBodyJson(), hit.similarity(), key, allowed, domainRequest.stream());
        }
        // Miss or Bypass: no cached response available, dispatch to the provider normally.
        return domainRequest.stream()
                ? streamingResponse(domainRequest, key, allowed, estimatedTokens, cacheLookup)
                : nonStreamingResponse(domainRequest, key, allowed, cacheLookup);
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

    // ---- Cache hits: F4.8 replay for streaming requests, direct body otherwise ----

    private Mono<ResponseEntity<Object>> cacheHitResponse(
            String responseBodyJson, Double similarity, ApiKeyContext key, QuotaDecision.Allowed allowed, boolean streaming) {
        // F4: a cache hit consumed no real provider tokens; reconcile the
        // reservation down to 0 so the cost saving is real, not just
        // avoided latency.
        if (key != null && allowed != null) {
            quotaPort.reconcile(key.keyId(), allowed.requestId(), 0);
            quotaPort.releaseConcurrencySlot(key.keyId(), allowed.requestId());
        }
        ChatCompletionResponseDto dto = mapper.responseFromJson(responseBodyJson);
        String cacheHeaderValue = similarity != null ? "SEMANTIC_HIT" : "EXACT_HIT";

        if (!streaming) {
            ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                    .header(CACHE_HEADER, cacheHeaderValue);
            if (similarity != null) {
                builder.header(SIMILARITY_HEADER, String.valueOf(similarity));
            }
            quotaHeader(builder, allowed);
            return Mono.just(builder.body((Object) dto));
        }

        String content = dto.choices().isEmpty() ? "" : dto.choices().get(0).message().content();
        String[] words = content.isEmpty() ? new String[0] : content.split("(?<=\\s)");
        String id = dto.id();

        Flux<ServerSentEvent<String>> body = Flux.fromArray(words)
                .delayElements(STREAM_REPLAY_WORD_DELAY)
                .map(word -> streamChunkEvent(id, dto.model(), word, false))
                .concatWith(Mono.just(streamChunkEvent(id, dto.model(), "", true)))
                .concatWith(Mono.just(ServerSentEvent.<String>builder("[DONE]").build()));

        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .header(CACHE_HEADER, cacheHeaderValue)
                .contentType(MediaType.TEXT_EVENT_STREAM);
        if (similarity != null) {
            builder.header(SIMILARITY_HEADER, String.valueOf(similarity));
        }
        quotaHeader(builder, allowed);
        return Mono.just(builder.body((Object) body));
    }

    private ServerSentEvent<String> streamChunkEvent(String id, String model, String deltaContent, boolean last) {
        var delta = new DeltaDto(deltaContent);
        var choice = new StreamChoiceDto(0, delta, last ? "stop" : null);
        var chunk = new StreamChunkResponseDto(id, "chat.completion.chunk", Instant.now().getEpochSecond(), model, List.of(choice));
        return ServerSentEvent.builder(mapper.toJson(chunk)).build();
    }

    // ---- Cache miss / bypass: normal dispatch to the provider, storing on a genuine Miss only ----

    private Mono<ResponseEntity<Object>> nonStreamingResponse(
            ChatCompletionRequest domainRequest, ApiKeyContext key, QuotaDecision.Allowed allowed, CacheLookup cacheLookup) {
        return Mono.fromCallable(() -> chatCompletionUseCase.complete(domainRequest))
                .subscribeOn(virtualThreadScheduler)
                .doOnNext(response -> {
                    reconcileAndRelease(key, allowed, response);
                    maybeStore(cacheLookup, domainRequest, response);
                })
                .doOnError(error -> reconcileAndReleaseOnFailure(key, allowed))
                .map(response -> toResponseEntity(response, allowed, cacheLookup.decision().headerValue()));
    }

    /** F4.5/F4.6: only ever stores on a genuine cache Miss (never Bypass) and only ever a successful, non-error completion. */
    private void maybeStore(CacheLookup cacheLookup, ChatCompletionRequest domainRequest, ProviderResponse response) {
        if (!(cacheLookup.decision() instanceof CacheDecision.Miss)) {
            return;
        }
        if (response instanceof ProviderResponse.Completion completion) {
            String responseJson = mapper.toJson(mapper.toDto(completion.response()));
            cachePort.store(cacheLookup.namespace(), domainRequest, responseJson, cacheLookup.routeCache().ttl());
        }
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

    private ResponseEntity<Object> toResponseEntity(ProviderResponse providerResponse, QuotaDecision.Allowed allowed, String cacheHeaderValue) {
        return switch (providerResponse) {
            case ProviderResponse.Completion completion -> {
                var builder = ResponseEntity.ok().header(CACHE_HEADER, cacheHeaderValue);
                quotaHeader(builder, allowed);
                yield builder.body((Object) mapper.toDto(completion.response()));
            }
            case ProviderResponse.ProviderError error -> {
                var status = HttpStatus.valueOf(error.httpStatus());
                var body = new ErrorEnvelopeDto(
                        "https://aether.dev/problems/" + error.errorCode(),
                        error.errorCode(),
                        error.httpStatus(),
                        error.message(),
                        "/v1/chat/completions");
                var builder = ResponseEntity.status(status).header(CACHE_HEADER, cacheHeaderValue);
                quotaHeader(builder, allowed);
                yield builder.body((Object) body);
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
            ChatCompletionRequest domainRequest, ApiKeyContext key, QuotaDecision.Allowed allowed, long estimatedTokens, CacheLookup cacheLookup) {
        // F1.5: capture partial usage (here, chunk count as a token-count
        // proxy) when a stream is cancelled mid-way. In-process logging is
        // sufficient at this phase; persistence into the request log
        // arrives with Phase 08 (M5).
        AtomicInteger chunksSent = new AtomicInteger(0);
        StringBuilder accumulated = new StringBuilder();
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicBoolean sawError = new AtomicBoolean(false);
        String streamId = "aether-" + UUID.randomUUID();

        Flux<ServerSentEvent<String>> body = JdkFlowAdapter
                .flowPublisherToFlux(chatStreamUseCase.stream(domainRequest))
                .doOnNext(chunk -> {
                    chunksSent.incrementAndGet();
                    accumulated.append(chunk.deltaContent());
                })
                .doOnError(e -> sawError.set(true))
                .map(this::toSseEvent)
                .concatWith(Mono.just(ServerSentEvent.<String>builder("[DONE]").build()))
                .doOnCancel(() -> {
                    cancelled.set(true);
                    log.info("Stream for model {} cancelled by client after {} chunks", domainRequest.model(), chunksSent.get());
                })
                // Streaming responses don't surface actual token usage at
                // this layer yet (Phase 08); reconcile with the original
                // estimate (a no-op adjustment) so the reservation isn't
                // left dangling until its 5-minute TTL expires, and always
                // free the concurrency slot regardless of how the stream
                // ended (completed, errored, or cancelled by the client).
                .doFinally(signal -> {
                    releaseStreamQuota(key, allowed, estimatedTokens);
                    // F4.6: never cache a cancelled stream or one that errored.
                    if (cacheLookup.decision() instanceof CacheDecision.Miss && !cancelled.get() && !sawError.get() && chunksSent.get() > 0) {
                        storeStreamedResponse(cacheLookup, domainRequest, streamId, accumulated.toString());
                    }
                });

        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .header(CACHE_HEADER, cacheLookup.decision().headerValue())
                .contentType(MediaType.TEXT_EVENT_STREAM);
        quotaHeader(builder, allowed);
        return Mono.just(builder.body((Object) body));
    }

    private void storeStreamedResponse(CacheLookup cacheLookup, ChatCompletionRequest domainRequest, String streamId, String fullContent) {
        var message = new ChatMessageDto("assistant", fullContent);
        var choice = new ChoiceDto(0, message, "stop");
        // Streaming responses don't surface actual token usage at this
        // layer (Phase 08 territory); a zero Usage is an accepted
        // simplification here since the cache subsystem only needs the
        // response body content and doesn't compute cost from this path.
        var dto = new ChatCompletionResponseDto(
                streamId, "chat.completion", Instant.now().getEpochSecond(), domainRequest.model(), List.of(choice), new UsageDto(0, 0, 0));
        cachePort.store(cacheLookup.namespace(), domainRequest, mapper.toJson(dto), cacheLookup.routeCache().ttl());
    }

    private ServerSentEvent<String> toSseEvent(ProviderResponse.StreamChunk chunk) {
        return ServerSentEvent.builder(mapper.toJson(mapper.toDto(chunk))).build();
    }
}
