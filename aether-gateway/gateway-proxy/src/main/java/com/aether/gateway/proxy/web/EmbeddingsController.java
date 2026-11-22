package com.aether.gateway.proxy.web;

import com.aether.gateway.core.domain.ApiKeyContext;
import com.aether.gateway.core.domain.CostCalculator;
import com.aether.gateway.core.domain.EmbeddingOutcome;
import com.aether.gateway.core.domain.EmbeddingRequest;
import com.aether.gateway.core.domain.ProviderResponse;
import com.aether.gateway.core.domain.RequestLogEntry;
import com.aether.gateway.core.domain.RequestMetrics;
import com.aether.gateway.core.port.CostModelPort;
import com.aether.gateway.core.port.EmbeddingUseCase;
import com.aether.gateway.core.port.MetricsPort;
import com.aether.gateway.core.port.RequestLogPort;
import com.aether.gateway.proxy.web.EmbeddingsDtoMapper.InvalidEmbeddingRequestException;
import com.aether.gateway.proxy.web.RequestAdmission.Admission;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * OpenAI-compatible {@code POST /v1/embeddings}: same authentication,
 * quota and provider-chain failover as chat, with the reservation sized
 * to the estimated input tokens (an embedding has no output tokens).
 *
 * <p>Deliberately no cache: an embedding is cheap, deterministic per
 * model, and typically requested once per document by an indexing job,
 * so a cache would mostly hold vectors nobody asks for twice, and the
 * existing semantic cache is keyed on chat messages anyway.
 */
@RestController
public class EmbeddingsController {

    private static final String PROVIDER_HEADER = "X-Aether-Provider";
    private static final String ATTEMPTS_HEADER = "X-Aether-Attempts";
    private static final String COST_HEADER = "X-Aether-Cost-USD";
    private static final String QUOTA_REMAINING_HEADER = "X-Aether-Quota-Remaining";
    private static final UUID ANONYMOUS_API_KEY_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");
    private static final String ANONYMOUS_TAG = "anonymous";
    private static final String NO_CACHE = "BYPASS";

    private final EmbeddingUseCase embeddingUseCase;
    private final RequestAdmission requestAdmission;
    private final MetricsPort metricsPort;
    private final RequestLogPort requestLogPort;
    private final CostModelPort costModelPort;
    private final Scheduler virtualThreadScheduler;

    public EmbeddingsController(
            EmbeddingUseCase embeddingUseCase,
            RequestAdmission requestAdmission,
            MetricsPort metricsPort,
            RequestLogPort requestLogPort,
            CostModelPort costModelPort,
            Scheduler virtualThreadScheduler) {
        this.embeddingUseCase = embeddingUseCase;
        this.requestAdmission = requestAdmission;
        this.metricsPort = metricsPort;
        this.requestLogPort = requestLogPort;
        this.costModelPort = costModelPort;
        this.virtualThreadScheduler = virtualThreadScheduler;
    }

    private record Outcome(String provider, String model, long inputTokens, BigDecimal costUsd, int attempts,
                           List<String> failoverChain, String status, String errorCode) {
    }

    @PostMapping("/v1/embeddings")
    public Mono<ResponseEntity<Object>> embed(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody EmbeddingsRequestDto requestDto,
            ServerHttpRequest httpRequest) {
        long startNanos = System.nanoTime();
        String clientIp = clientIp(httpRequest);
        return Mono.fromCallable(() -> handle(authorization, clientIp, requestDto, startNanos))
                .subscribeOn(virtualThreadScheduler)
                .onErrorResume(InvalidEmbeddingRequestException.class,
                        e -> Mono.just(error(400, "invalid_request", e.getMessage(), e.param)))
                .onErrorResume(IllegalArgumentException.class,
                        e -> Mono.just(error(400, "invalid_request", e.getMessage(), null)));
    }

    private ResponseEntity<Object> handle(String authorization, String clientIp, EmbeddingsRequestDto dto, long startNanos) {
        EmbeddingRequest request = EmbeddingsDtoMapper.toDomain(dto);
        Admission admission = requestAdmission.admit(authorization, clientIp, request.estimatedPromptTokens());
        if (admission instanceof Admission.Refused refused) {
            record(request, admission, new Outcome(null, request.model(), 0, BigDecimal.ZERO, 0, List.of(),
                    refusalStatus(refused), refused.code()), startNanos);
            return refusal(refused);
        }

        EmbeddingOutcome outcome;
        try {
            outcome = embeddingUseCase.embed(request);
        } catch (RuntimeException e) {
            requestAdmission.settle(admission, 0);
            throw e;
        }
        return switch (outcome) {
            case EmbeddingOutcome.Success success -> {
                requestAdmission.settle(admission, success.response().promptTokens());
                BigDecimal cost = costOf(success, request.model());
                record(request, admission, new Outcome(success.servedByProvider(), success.servedModel(),
                        success.response().promptTokens(), cost, success.attemptCount(), success.failoverChain(), "OK", null), startNanos);
                var builder = ResponseEntity.ok()
                        .header(ATTEMPTS_HEADER, String.valueOf(success.attemptCount()))
                        .header(COST_HEADER, cost.stripTrailingZeros().toPlainString());
                if (success.servedByProvider() != null) {
                    builder.header(PROVIDER_HEADER, success.servedByProvider());
                }
                quotaHeader(builder, admission);
                yield builder.body((Object) EmbeddingsDtoMapper.toDto(success.response(), EmbeddingsDtoMapper.wantsBase64(dto)));
            }
            case EmbeddingOutcome.Failure failure -> {
                requestAdmission.settle(admission, 0);
                ProviderResponse.ProviderError error = failure.error();
                record(request, admission, new Outcome(null, request.model(), 0, BigDecimal.ZERO, error.attemptCount(),
                        error.failoverChain(), "FAILED", error.errorCode()), startNanos);
                var builder = ResponseEntity.status(error.httpStatus())
                        .header(ATTEMPTS_HEADER, String.valueOf(error.attemptCount()));
                quotaHeader(builder, admission);
                yield builder.body((Object) EmbeddingsErrorDto.of(error.httpStatus(), error.errorCode(), error.message(), null));
            }
        };
    }

    /**
     * Priced by the upstream model that actually served the call, falling
     * back to the route alias (the key the chat path prices by), so either
     * style of cost-model entry works. Unpriced models cost 0.
     */
    private BigDecimal costOf(EmbeddingOutcome.Success success, String alias) {
        String provider = success.servedByProvider();
        if (provider == null) {
            return BigDecimal.ZERO;
        }
        return costModelPort.pricingFor(provider, success.servedModel() != null ? success.servedModel() : alias)
                .or(() -> costModelPort.pricingFor(provider, alias))
                .map(pricing -> CostCalculator.costUsd(pricing, success.response().promptTokens(), 0))
                .orElse(BigDecimal.ZERO);
    }

    /** Same request_log status vocabulary the chat endpoint writes for its refusals. */
    private static String refusalStatus(Admission.Refused refused) {
        return switch (refused.code()) {
            case "unauthenticated" -> "UNAUTHENTICATED";
            case "ip_rate_limited" -> "IP_RATE_LIMITED";
            default -> "QUOTA_EXCEEDED";
        };
    }

    private ResponseEntity<Object> refusal(Admission.Refused refused) {
        var builder = ResponseEntity.status(refused.status());
        if (refused.status() == 401) {
            builder.header("WWW-Authenticate", "Bearer realm=\"aether-gateway\"");
        }
        if (refused.remainingQuota() != null) {
            builder.header(QUOTA_REMAINING_HEADER, String.valueOf(refused.remainingQuota()));
        }
        return builder.body(EmbeddingsErrorDto.of(refused.status(), refused.code(), refused.detail(), null));
    }

    private static ResponseEntity<Object> error(int status, String code, String message, String param) {
        return ResponseEntity.status(status).body(EmbeddingsErrorDto.of(status, code, message, param));
    }

    private static void quotaHeader(ResponseEntity.BodyBuilder builder, Admission admission) {
        if (admission instanceof Admission.Admitted admitted) {
            builder.header(QUOTA_REMAINING_HEADER, String.valueOf(admitted.allowed().remainingMonthlyTokens()));
        }
    }

    private void record(EmbeddingRequest request, Admission admission, Outcome outcome, long startNanos) {
        ApiKeyContext key = admission instanceof Admission.Admitted admitted ? admitted.key() : null;
        int totalMs = (int) ((System.nanoTime() - startNanos) / 1_000_000);
        requestLogPort.log(new RequestLogEntry(
                UUID.randomUUID(), key != null ? UUID.fromString(key.keyId()) : ANONYMOUS_API_KEY_ID, null,
                request.model(), outcome.provider(), outcome.model(), null, null, false, NO_CACHE, null,
                (int) outcome.inputTokens(), 0, outcome.costUsd(), BigDecimal.ZERO, totalMs, totalMs,
                outcome.attempts(), outcome.failoverChain(), outcome.status(), outcome.errorCode(), Instant.now()));
        metricsPort.recordRequest(new RequestMetrics(
                outcome.provider(), outcome.model(), request.model(), key != null ? key.keyId() : ANONYMOUS_TAG,
                NO_CACHE, outcome.status(), totalMs, outcome.inputTokens(), 0, outcome.costUsd().doubleValue(), 0));
    }

    /** Remote address only, never X-Forwarded-For, for the same reason as the chat endpoint. */
    private static String clientIp(ServerHttpRequest request) {
        var remoteAddress = request.getRemoteAddress();
        return remoteAddress != null && remoteAddress.getAddress() != null
                ? remoteAddress.getAddress().getHostAddress() : "unknown";
    }
}
