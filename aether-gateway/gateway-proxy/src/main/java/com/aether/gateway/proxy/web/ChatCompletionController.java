package com.aether.gateway.proxy.web;

import com.aether.gateway.core.domain.ApiKeyContext;
import com.aether.gateway.core.domain.CacheDecision;
import com.aether.gateway.core.domain.ChatCompletionRequest;
import com.aether.gateway.core.domain.ChatMessage;
import com.aether.gateway.core.domain.CostCalculator;
import com.aether.gateway.core.domain.ModelPricing;
import com.aether.gateway.core.domain.PromptNotFoundException;
import com.aether.gateway.core.domain.PromptReference;
import com.aether.gateway.core.domain.PromptTemplateRenderer;
import com.aether.gateway.core.domain.PromptVariableValidationException;
import com.aether.gateway.core.domain.PromptVersion;
import com.aether.gateway.core.domain.ProviderResponse;
import com.aether.gateway.core.domain.QuotaDecision;
import com.aether.gateway.core.domain.RequestLogEntry;
import com.aether.gateway.core.domain.RequestMetrics;
import com.aether.gateway.core.domain.RouteCacheConfig;
import com.aether.gateway.core.domain.RouteConfig;
import com.aether.gateway.core.domain.TokenEstimator;
import com.aether.gateway.core.port.ApiKeyLookupPort;
import com.aether.gateway.core.port.CachePort;
import com.aether.gateway.core.port.ChatCompletionUseCase;
import com.aether.gateway.core.port.ChatStreamUseCase;
import com.aether.gateway.core.port.CostModelPort;
import com.aether.gateway.core.port.IpRateLimitPort;
import com.aether.gateway.core.port.MetricsPort;
import com.aether.gateway.core.port.PromptRegistryPort;
import com.aether.gateway.core.port.QuotaPort;
import com.aether.gateway.core.port.RequestLogPort;
import com.aether.gateway.router.routing.RoutingSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.server.reactive.ServerHttpRequest;
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

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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
    private static final String PROVIDER_HEADER = "X-Aether-Provider";
    private static final String ATTEMPTS_HEADER = "X-Aether-Attempts";
    private static final String COST_HEADER = "X-Aether-Cost-USD";
    private static final String ANONYMOUS_NAMESPACE = "anonymous";
    // Phase 08 (M5): request_log.api_key_id is NOT NULL; this is the
    // well-known, disabled placeholder row seeded by
    // db/migrations/V5__anonymous_api_key.sql for exactly this purpose.
    private static final UUID ANONYMOUS_API_KEY_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");
    private static final Duration STREAM_REPLAY_WORD_DELAY = Duration.ofMillis(25);

    private final ChatCompletionUseCase chatCompletionUseCase;
    private final ChatStreamUseCase chatStreamUseCase;
    private final ApiKeyLookupPort apiKeyLookupPort;
    private final QuotaPort quotaPort;
    private final IpRateLimitPort ipRateLimitPort;
    private final TokenEstimator tokenEstimator;
    private final CachePort cachePort;
    private final RoutingSource routingSource;
    private final MetricsPort metricsPort;
    private final RequestLogPort requestLogPort;
    private final CostModelPort costModelPort;
    private final PromptRegistryPort promptRegistryPort;
    private final ChatCompletionDtoMapper mapper;
    private final Scheduler virtualThreadScheduler;
    private final boolean allowAnonymous;

    public ChatCompletionController(
            ChatCompletionUseCase chatCompletionUseCase,
            ChatStreamUseCase chatStreamUseCase,
            ApiKeyLookupPort apiKeyLookupPort,
            QuotaPort quotaPort,
            IpRateLimitPort ipRateLimitPort,
            TokenEstimator tokenEstimator,
            CachePort cachePort,
            RoutingSource routingSource,
            MetricsPort metricsPort,
            RequestLogPort requestLogPort,
            CostModelPort costModelPort,
            PromptRegistryPort promptRegistryPort,
            ChatCompletionDtoMapper mapper,
            Scheduler virtualThreadScheduler,
            @Value("${aether.security.allow-anonymous:false}") boolean allowAnonymous) {
        this.chatCompletionUseCase = chatCompletionUseCase;
        this.chatStreamUseCase = chatStreamUseCase;
        this.apiKeyLookupPort = apiKeyLookupPort;
        this.quotaPort = quotaPort;
        this.ipRateLimitPort = ipRateLimitPort;
        this.tokenEstimator = tokenEstimator;
        this.cachePort = cachePort;
        this.routingSource = routingSource;
        this.metricsPort = metricsPort;
        this.requestLogPort = requestLogPort;
        this.costModelPort = costModelPort;
        this.promptRegistryPort = promptRegistryPort;
        this.mapper = mapper;
        this.virtualThreadScheduler = virtualThreadScheduler;
        this.allowAnonymous = allowAnonymous;
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
            @RequestHeader(value = "X-Aether-Prompt", required = false) String promptReferenceHeader,
            @RequestBody ChatCompletionRequestDto requestDto,
            ServerHttpRequest httpRequest) {
        String clientIp = clientIp(httpRequest);
        boolean noCachePresent = noCacheHeader != null;
        long requestStartNanos = System.nanoTime();
        // F7.2/F7.4: captured here (not threaded through every downstream
        // method signature) and read once at the very end to attach
        // X-Aether-Prompt-Version - the header is the exit criterion's
        // observable proof that a rollback took effect on the very next
        // request, no gateway restart. A fresh AtomicReference per call,
        // so no cross-request state leaks between concurrent requests.
        java.util.concurrent.atomic.AtomicReference<PromptVersion> resolvedPromptVersion = new java.util.concurrent.atomic.AtomicReference<>();
        return Mono.fromCallable(() -> resolveMessages(promptReferenceHeader, requestDto, resolvedPromptVersion))
                .subscribeOn(virtualThreadScheduler)
                .flatMap(effectiveDto -> {
                    ChatCompletionRequest domainRequest = mapper.toDomain(effectiveDto);
                    return Mono.fromCallable(() -> checkQuota(authorization, clientIp, domainRequest))
                            .subscribeOn(virtualThreadScheduler)
                            .flatMap(quotaOutcome -> Mono
                                    .fromCallable(() -> checkCache(quotaOutcome, domainRequest, noCachePresent, thresholdOverride))
                                    .subscribeOn(virtualThreadScheduler)
                                    .flatMap(cacheLookup -> dispatch(quotaOutcome, domainRequest, cacheLookup, requestStartNanos)));
                })
                .map(response -> attachPromptVersionHeader(response, resolvedPromptVersion.get()))
                .onErrorResume(PromptNotFoundException.class,
                        e -> Mono.just(promptErrorResponse(HttpStatus.NOT_FOUND, "prompt_not_found", e.getMessage())))
                .onErrorResume(PromptVariableValidationException.class,
                        e -> Mono.just(promptErrorResponse(HttpStatus.BAD_REQUEST, "prompt_variable_validation_failed", e.getMessage())))
                // F9.4 and pre-existing ChatCompletionRequest validation
                // (blank model, empty messages): previously fell through
                // unmapped to a generic 500 - this is where the domain's
                // IllegalArgumentException actually needed to surface as
                // a client error all along.
                .onErrorResume(IllegalArgumentException.class,
                        e -> Mono.just(promptErrorResponse(HttpStatus.BAD_REQUEST, "invalid_request", e.getMessage())));
    }

    private ResponseEntity<Object> attachPromptVersionHeader(ResponseEntity<Object> response, PromptVersion version) {
        if (version == null) {
            return response;
        }
        return ResponseEntity.status(response.getStatusCode())
                .headers(response.getHeaders())
                .header("X-Aether-Prompt-Version", String.valueOf(version.version()))
                .body(response.getBody());
    }

    /**
     * F7.2: when {@code X-Aether-Prompt} is present, resolves and
     * renders the referenced prompt version (F7.6's strict variable
     * validation applies here) and returns a DTO with {@code messages}
     * replaced by the rendered result; the request's own {@code
     * messages} field, if any, is ignored in that case. Absent the
     * header, the DTO passes through unchanged - fully backward
     * compatible with raw-message requests.
     */
    private ChatCompletionRequestDto resolveMessages(
            String promptReferenceHeader, ChatCompletionRequestDto requestDto, java.util.concurrent.atomic.AtomicReference<PromptVersion> resolvedPromptVersion) {
        if (promptReferenceHeader == null) {
            return requestDto;
        }
        PromptReference reference = PromptReference.parse(promptReferenceHeader);
        PromptVersion version = reference.version()
                .map(v -> promptRegistryPort.findByNameAndVersion(reference.promptName(), v))
                .orElseGet(() -> promptRegistryPort.findByNameAndAlias(reference.promptName(), reference.alias().orElseThrow()))
                .orElseThrow(() -> new PromptNotFoundException(promptReferenceHeader));
        resolvedPromptVersion.set(version);

        Map<String, String> variables = requestDto.variables() != null ? requestDto.variables() : Map.of();
        List<ChatMessage> rendered = PromptTemplateRenderer.render(version.template(), version.variables(), variables);
        List<ChatMessageDto> renderedMessages = rendered.stream()
                .map(m -> new ChatMessageDto(m.role(), m.content()))
                .toList();
        return new ChatCompletionRequestDto(
                requestDto.model(), renderedMessages, requestDto.stream(), requestDto.temperature(), requestDto.tools(), requestDto.variables());
    }

    private ResponseEntity<Object> promptErrorResponse(HttpStatus status, String errorCode, String message) {
        var body = new ErrorEnvelopeDto(
                "https://aether.dev/problems/" + errorCode, errorCode, status.value(), message, "/v1/chat/completions");
        return ResponseEntity.status(status).body((Object) body);
    }

    private sealed interface QuotaOutcome {
        record Unmetered() implements QuotaOutcome {
        }

        record Proceed(ApiKeyContext key, QuotaDecision.Allowed allowed, long estimatedTokens) implements QuotaOutcome {
        }

        record Rejected(QuotaDecision.Rejected rejected) implements QuotaOutcome {
        }

        /** F9.5: the anonymous path only - a real API key is already covered by {@link Rejected}'s per-key quota. */
        record IpRateLimited() implements QuotaOutcome {
        }

        /** No usable API key, and this deployment does not serve anonymous traffic. */
        record Unauthenticated() implements QuotaOutcome {
        }
    }

    private record CacheLookup(String namespace, CacheDecision decision, RouteCacheConfig routeCache) {
    }

    /**
     * F9.5: extracted only from the connection's own remote address, not
     * an {@code X-Forwarded-For}-style header - this service has no
     * documented trusted-reverse-proxy configuration, and honouring a
     * client-controlled header here would make the limit trivially
     * bypassable by whoever it's meant to constrain.
     */
    private String clientIp(ServerHttpRequest request) {
        var remoteAddress = request.getRemoteAddress();
        return remoteAddress != null && remoteAddress.getAddress() != null
                ? remoteAddress.getAddress().getHostAddress() : "unknown";
    }

    private QuotaOutcome checkQuota(String authorization, String clientIp, ChatCompletionRequest domainRequest) {
        if (authorization == null || authorization.isBlank()) {
            return anonymousOutcome(clientIp);
        }
        String rawKey = authorization.startsWith("Bearer ") ? authorization.substring(7) : authorization;
        ApiKeyContext key = apiKeyLookupPort.findByRawKey(rawKey).orElse(null);
        if (key == null) {
            return anonymousOutcome(clientIp);
        }
        return checkQuotaForKey(key, domainRequest);
    }

    /**
     * A request with no key, or one this gateway does not recognise, is
     * unmetered: no quota bounds it, no budget is charged, and its usage
     * is attributable to nobody. Serving it means proxying to whatever
     * real provider credentials the operator configured, so it is
     * refused unless the operator has explicitly opted in.
     *
     * <p>An unrecognised key is deliberately treated exactly like no key
     * at all: leaking "that key exists but is wrong" versus "that key
     * does not exist" would turn this endpoint into a key oracle.
     */
    private QuotaOutcome anonymousOutcome(String clientIp) {
        if (!allowAnonymous) {
            return new QuotaOutcome.Unauthenticated();
        }
        return ipRateLimitPort.tryConsume(clientIp) ? new QuotaOutcome.Unmetered() : new QuotaOutcome.IpRateLimited();
    }

    private QuotaOutcome checkQuotaForKey(ApiKeyContext key, ChatCompletionRequest domainRequest) {
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
        if (quotaOutcome instanceof QuotaOutcome.IpRateLimited) {
            return new CacheLookup(ANONYMOUS_NAMESPACE, new CacheDecision.Bypass("ip_rate_limited"), RouteCacheConfig.DISABLED);
        }
        if (quotaOutcome instanceof QuotaOutcome.Unauthenticated) {
            return new CacheLookup(ANONYMOUS_NAMESPACE, new CacheDecision.Bypass("unauthenticated"), RouteCacheConfig.DISABLED);
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

    private Mono<ResponseEntity<Object>> dispatch(
            QuotaOutcome quotaOutcome, ChatCompletionRequest domainRequest, CacheLookup cacheLookup, long requestStartNanos) {
        if (quotaOutcome instanceof QuotaOutcome.Rejected rejected) {
            return Mono.just(quotaRejectedResponse(rejected.rejected(), domainRequest, requestStartNanos));
        }
        if (quotaOutcome instanceof QuotaOutcome.IpRateLimited) {
            return Mono.just(ipRateLimitedResponse(domainRequest, requestStartNanos));
        }
        if (quotaOutcome instanceof QuotaOutcome.Unauthenticated) {
            return Mono.just(unauthenticatedResponse(domainRequest, requestStartNanos));
        }
        ApiKeyContext key = quotaOutcome instanceof QuotaOutcome.Proceed proceed ? proceed.key() : null;
        QuotaDecision.Allowed allowed = quotaOutcome instanceof QuotaOutcome.Proceed proceed ? proceed.allowed() : null;
        long estimatedTokens = quotaOutcome instanceof QuotaOutcome.Proceed proceed ? proceed.estimatedTokens() : 0;

        if (cacheLookup.decision() instanceof CacheDecision.ExactHit hit) {
            return cacheHitResponse(hit.responseBodyJson(), null, key, allowed, domainRequest, cacheLookup, requestStartNanos);
        }
        if (cacheLookup.decision() instanceof CacheDecision.SemanticHit hit) {
            return cacheHitResponse(hit.responseBodyJson(), hit.similarity(), key, allowed, domainRequest, cacheLookup, requestStartNanos);
        }
        // Miss or Bypass: no cached response available, dispatch to the provider normally.
        return domainRequest.stream()
                ? streamingResponse(domainRequest, key, allowed, estimatedTokens, cacheLookup, requestStartNanos)
                : nonStreamingResponse(domainRequest, key, allowed, cacheLookup, requestStartNanos);
    }

    private ResponseEntity<Object> quotaRejectedResponse(QuotaDecision.Rejected rejected, ChatCompletionRequest domainRequest, long requestStartNanos) {
        var body = new ErrorEnvelopeDto(
                "https://aether.dev/problems/quota_exceeded",
                rejected.reason(),
                HttpStatus.TOO_MANY_REQUESTS.value(),
                "Quota exceeded: " + rejected.reason(),
                "/v1/chat/completions");
        long totalMs = elapsedMillis(requestStartNanos);
        recordObservability(domainRequest, null, null, "BYPASS", false, null,
                0, 0, totalMs, totalMs, 0, List.of(), "QUOTA_EXCEEDED", rejected.reason());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(QUOTA_REMAINING_HEADER, String.valueOf(rejected.remainingMonthlyTokens()))
                .body((Object) body);
    }

    /** F9.5: the anonymous per-IP limit was exceeded - no API key involved at all, so none of {@link #quotaRejectedResponse}'s per-key fields apply. */
    private ResponseEntity<Object> ipRateLimitedResponse(ChatCompletionRequest domainRequest, long requestStartNanos) {
        var body = new ErrorEnvelopeDto(
                "https://aether.dev/problems/ip_rate_limited",
                "ip_rate_limited",
                HttpStatus.TOO_MANY_REQUESTS.value(),
                "Too many unauthenticated requests from this address",
                "/v1/chat/completions");
        long totalMs = elapsedMillis(requestStartNanos);
        recordObservability(domainRequest, null, null, "BYPASS", false, null,
                0, 0, totalMs, totalMs, 0, List.of(), "IP_RATE_LIMITED", "ip_rate_limited");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body((Object) body);
    }

    /**
     * No usable API key on a gateway that does not serve anonymous
     * traffic. Logged like any other refusal so operators can see
     * unauthenticated attempts building up rather than having them
     * vanish before the request log.
     */
    private ResponseEntity<Object> unauthenticatedResponse(ChatCompletionRequest domainRequest, long requestStartNanos) {
        var body = new ErrorEnvelopeDto(
                "https://aether.dev/problems/unauthenticated",
                "unauthenticated",
                HttpStatus.UNAUTHORIZED.value(),
                "A valid API key is required. Send it as 'Authorization: Bearer <key>'.",
                "/v1/chat/completions");
        long totalMs = elapsedMillis(requestStartNanos);
        recordObservability(domainRequest, null, null, "BYPASS", false, null,
                0, 0, totalMs, totalMs, 0, List.of(), "UNAUTHENTICATED", "unauthenticated");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header("WWW-Authenticate", "Bearer realm=\"aether-gateway\"")
                .body((Object) body);
    }

    // ---- Cache hits: F4.8 replay for streaming requests, direct body otherwise ----

    private Mono<ResponseEntity<Object>> cacheHitResponse(
            String responseBodyJson, Double similarity, ApiKeyContext key, QuotaDecision.Allowed allowed,
            ChatCompletionRequest domainRequest, CacheLookup cacheLookup, long requestStartNanos) {
        boolean streaming = domainRequest.stream();
        // F4: a cache hit consumed no real provider tokens; reconcile the
        // reservation down to 0 so the cost saving is real, not just
        // avoided latency.
        if (key != null && allowed != null) {
            quotaPort.reconcile(key.keyId(), allowed.requestId(), 0);
            quotaPort.releaseConcurrencySlot(key.keyId(), allowed.requestId());
        }
        ChatCompletionResponseDto dto = mapper.responseFromJson(responseBodyJson);
        String cacheHeaderValue = similarity != null ? "SEMANTIC_HIT" : "EXACT_HIT";
        long inputTokens = dto.usage() != null ? dto.usage().promptTokens() : 0;
        long outputTokens = dto.usage() != null ? dto.usage().completionTokens() : 0;
        long totalMs = elapsedMillis(requestStartNanos);
        CostOutcome cost = recordObservability(
                domainRequest, key, null, cacheHeaderValue, true, similarity,
                inputTokens, outputTokens, totalMs, totalMs, 1, List.of(),
                "OK", null);

        if (!streaming) {
            ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                    .header(CACHE_HEADER, cacheHeaderValue)
                    .header(COST_HEADER, cost.costUsd().toPlainString());
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
                .header(COST_HEADER, cost.costUsd().toPlainString())
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
            ChatCompletionRequest domainRequest, ApiKeyContext key, QuotaDecision.Allowed allowed, CacheLookup cacheLookup, long requestStartNanos) {
        return Mono.fromCallable(() -> chatCompletionUseCase.complete(domainRequest))
                .subscribeOn(virtualThreadScheduler)
                .doOnNext(response -> {
                    reconcileAndRelease(key, allowed, response);
                    maybeStore(cacheLookup, domainRequest, response);
                })
                .doOnError(error -> reconcileAndReleaseOnFailure(key, allowed))
                .map(response -> toResponseEntity(response, allowed, domainRequest, key, cacheLookup, requestStartNanos));
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

    private ResponseEntity<Object> toResponseEntity(
            ProviderResponse providerResponse, QuotaDecision.Allowed allowed, ChatCompletionRequest domainRequest,
            ApiKeyContext key, CacheLookup cacheLookup, long requestStartNanos) {
        String cacheHeaderValue = cacheLookup.decision().headerValue();
        long totalMs = elapsedMillis(requestStartNanos);
        return switch (providerResponse) {
            case ProviderResponse.Completion completion -> {
                CostOutcome cost = recordObservability(
                        domainRequest, key, completion.servedByProvider(), cacheHeaderValue, false, null,
                        completion.response().usage().promptTokens(), completion.response().usage().completionTokens(),
                        totalMs, totalMs, completion.attemptCount(), completion.failoverChain(), "OK", null);
                var builder = ResponseEntity.ok()
                        .header(CACHE_HEADER, cacheHeaderValue)
                        .header(COST_HEADER, cost.costUsd().toPlainString())
                        .header(ATTEMPTS_HEADER, String.valueOf(completion.attemptCount()));
                if (completion.servedByProvider() != null) {
                    builder.header(PROVIDER_HEADER, completion.servedByProvider());
                }
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
                recordObservability(domainRequest, key, null, cacheHeaderValue, false, null,
                        0, 0, totalMs, totalMs, error.attemptCount(), error.failoverChain(), "FAILED", error.errorCode());
                var builder = ResponseEntity.status(status)
                        .header(CACHE_HEADER, cacheHeaderValue)
                        .header(ATTEMPTS_HEADER, String.valueOf(error.attemptCount()));
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
            ChatCompletionRequest domainRequest, ApiKeyContext key, QuotaDecision.Allowed allowed, long estimatedTokens,
            CacheLookup cacheLookup, long requestStartNanos) {
        // F1.5: capture partial usage (here, chunk count as a token-count
        // proxy) when a stream is cancelled mid-way.
        AtomicInteger chunksSent = new AtomicInteger(0);
        StringBuilder accumulated = new StringBuilder();
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicBoolean sawError = new AtomicBoolean(false);
        AtomicLong ttfbNanos = new AtomicLong(-1);
        String streamId = "aether-" + UUID.randomUUID();
        metricsPort.streamStarted();

        Flux<ServerSentEvent<String>> body = JdkFlowAdapter
                .flowPublisherToFlux(chatStreamUseCase.stream(domainRequest))
                .doOnNext(chunk -> {
                    chunksSent.incrementAndGet();
                    accumulated.append(chunk.deltaContent());
                    ttfbNanos.compareAndSet(-1, System.nanoTime());
                })
                .doOnError(e -> sawError.set(true))
                .map(this::toSseEvent)
                .concatWith(Mono.just(ServerSentEvent.<String>builder("[DONE]").build()))
                .doOnCancel(() -> {
                    cancelled.set(true);
                    log.info("Stream for model {} cancelled by client after {} chunks", domainRequest.model(), chunksSent.get());
                })
                // Streaming responses don't surface actual token usage at
                // this layer yet (Phase 08 territory for a real per-token
                // count; F6.1/F6.2 still record what's available: an
                // estimated token count, real timing, and the terminal
                // status); reconcile with the original quota estimate (a
                // no-op adjustment) so the reservation isn't left dangling
                // until its 5-minute TTL expires, and always free the
                // concurrency slot regardless of how the stream ended
                // (completed, errored, or cancelled by the client).
                .doFinally(signal -> {
                    metricsPort.streamEnded();
                    releaseStreamQuota(key, allowed, estimatedTokens);
                    // F4.6: never cache a cancelled stream or one that errored.
                    if (cacheLookup.decision() instanceof CacheDecision.Miss && !cancelled.get() && !sawError.get() && chunksSent.get() > 0) {
                        storeStreamedResponse(cacheLookup, domainRequest, streamId, accumulated.toString());
                    }
                    String status = cancelled.get() ? "CANCELLED" : sawError.get() ? "FAILED" : "OK";
                    long totalMs = elapsedMillis(requestStartNanos);
                    long ttfbMs = ttfbNanos.get() < 0 ? totalMs : (ttfbNanos.get() - requestStartNanos) / 1_000_000;
                    recordObservability(domainRequest, key, null, cacheLookup.decision().headerValue(), false, null,
                            0, estimatedTokens, ttfbMs, totalMs, 1, List.of(), status, null);
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

    // ---- F6.1/F6.2/F6.3/F6.4: metrics + async request log, shared by every terminal outcome above ----

    private record CostOutcome(BigDecimal costUsd, BigDecimal savedUsd) {
    }

    /**
     * The single place every request outcome (quota-rejected, cache hit,
     * provider success, provider failure, streamed OK/CANCELLED/FAILED)
     * funnels through to emit both the Micrometer metrics (F6.1) and the
     * async request log entry (F6.2). Returns the computed cost/savings
     * split so callers can also surface {@code X-Aether-Cost-USD}
     * without recomputing it.
     */
    private CostOutcome recordObservability(
            ChatCompletionRequest domainRequest, ApiKeyContext key, String servedByProvider,
            String cacheOutcomeLabel, boolean isCacheHit, Double similarity,
            long inputTokens, long outputTokens, long ttfbMs, long totalMs,
            int attemptCount, List<String> failoverChain, String status, String errorCode) {

        ModelPricing pricing = resolvePricing(servedByProvider, domainRequest.model());
        BigDecimal amount = pricing != null ? CostCalculator.costUsd(pricing, inputTokens, outputTokens) : BigDecimal.ZERO;
        BigDecimal costUsd = isCacheHit ? BigDecimal.ZERO : amount;
        BigDecimal savedUsd = isCacheHit ? amount : BigDecimal.ZERO;
        // For a cache hit, servedByProvider is null (no provider call
        // happened); tag the log/metric with whichever provider's price
        // was actually used to compute the savings estimate, so "top
        // keys by cost" / spend-avoided panels attribute correctly
        // instead of grouping every cache hit under "unknown".
        String attributedProvider = servedByProvider != null ? servedByProvider : (pricing != null ? pricing.provider() : null);

        UUID apiKeyId = key != null ? UUID.fromString(key.keyId()) : ANONYMOUS_API_KEY_ID;
        String apiKeyTag = key != null ? key.keyId() : ANONYMOUS_NAMESPACE;

        requestLogPort.log(new RequestLogEntry(
                UUID.randomUUID(), apiKeyId, null, domainRequest.model(), attributedProvider, domainRequest.model(),
                null, null, domainRequest.stream(), cacheOutcomeLabel, similarity,
                (int) inputTokens, (int) outputTokens, costUsd, savedUsd,
                (int) ttfbMs, (int) totalMs, attemptCount, failoverChain,
                status, errorCode, Instant.now()));

        metricsPort.recordRequest(new RequestMetrics(
                attributedProvider, domainRequest.model(), domainRequest.model(), apiKeyTag,
                cacheOutcomeLabel, status, totalMs, inputTokens, outputTokens, costUsd.doubleValue(), savedUsd.doubleValue()));

        return new CostOutcome(costUsd, savedUsd);
    }

    /**
     * A real provider call already knows exactly which provider served
     * it. A cache hit does not (the stored response DTO carries no
     * provider attribution), so its cost/savings estimate instead uses
     * the route's primary (first chain member) provider's price for the
     * same model - a defensible stand-in for "what this would have cost
     * had it gone to the provider," which is F4.9/F6.4's requirement,
     * versus reporting no savings number at all.
     */
    private ModelPricing resolvePricing(String provider, String model) {
        if (provider != null) {
            return costModelPort.pricingFor(provider, model).orElse(null);
        }
        return routingSource.routeFor(model)
                .flatMap(route -> route.chain().stream().findFirst())
                .flatMap(member -> costModelPort.pricingFor(member.provider(), model))
                .orElse(null);
    }

    private long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
