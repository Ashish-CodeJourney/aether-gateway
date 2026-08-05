package com.aether.gateway.proxy.web;

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
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A request carrying no (or an unrecognised) API key used to be served
 * as unmetered traffic against whatever real provider credentials the
 * operator configured, guarded only by a per-IP bucket. Anyone exposing
 * a gateway inherited an open proxy to their own paid providers, and no
 * usage was attributed to anyone.
 *
 * <p>Anonymous access is therefore now opt-in. These tests pin both
 * postures: closed by default, and still functional for the deployments
 * (local demos, the acceptance suite) that deliberately turn it on.
 */
class ChatCompletionControllerAnonymousAccessTest {

    private final ChatCompletionUseCase chatCompletionUseCase = mock(ChatCompletionUseCase.class);
    private final ChatStreamUseCase chatStreamUseCase = mock(ChatStreamUseCase.class);
    private final ApiKeyLookupPort apiKeyLookupPort = mock(ApiKeyLookupPort.class);
    private final QuotaPort quotaPort = mock(QuotaPort.class);
    private final IpRateLimitPort ipRateLimitPort = mock(IpRateLimitPort.class);
    private final TokenEstimator tokenEstimator = mock(TokenEstimator.class);
    private final CachePort cachePort = mock(CachePort.class);
    private final RoutingSource routingSource = mock(RoutingSource.class);
    private final MetricsPort metricsPort = mock(MetricsPort.class);
    private final RequestLogPort requestLogPort = mock(RequestLogPort.class);
    private final CostModelPort costModelPort = mock(CostModelPort.class);
    private final PromptRegistryPort promptRegistryPort = mock(PromptRegistryPort.class);

    private WebTestClient clientWithAnonymousAccess(boolean allowAnonymous) {
        var controller = new ChatCompletionController(
                chatCompletionUseCase,
                chatStreamUseCase,
                apiKeyLookupPort,
                quotaPort,
                ipRateLimitPort,
                tokenEstimator,
                cachePort,
                routingSource,
                metricsPort,
                requestLogPort,
                costModelPort,
                promptRegistryPort,
                new ChatCompletionDtoMapper(),
                Schedulers.immediate(),
                allowAnonymous);
        return WebTestClient.bindToController(controller).build();
    }

    private static ChatCompletionRequestDto request() {
        return new ChatCompletionRequestDto(
                "mock", List.of(new ChatMessageDto("user", "hello")), false, null, null, null);
    }

    @Test
    void rejectsARequestWithNoApiKeyWhenAnonymousAccessIsOff() {
        clientWithAnonymousAccess(false).post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request())
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.title").isEqualTo("unauthenticated");
    }

    @Test
    void rejectsARequestWhoseApiKeyIsNotRecognisedWhenAnonymousAccessIsOff() {
        when(apiKeyLookupPort.findByRawKey(anyString())).thenReturn(Optional.empty());

        clientWithAnonymousAccess(false).post()
                .uri("/v1/chat/completions")
                .header("Authorization", "Bearer aeth_not_a_real_key")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request())
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void neverReachesAProviderOrTheQuotaLedgerForAnUnauthenticatedRequest() {
        clientWithAnonymousAccess(false).post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request())
                .exchange()
                .expectStatus().isUnauthorized();

        verify(chatCompletionUseCase, never()).complete(any());
        verify(quotaPort, never()).checkAndReserve(any(), anyString(), org.mockito.ArgumentMatchers.anyLong());
        verify(ipRateLimitPort, never()).tryConsume(anyString());
    }

    @Test
    void stillAdmitsAnonymousRequestsToTheMeteredPathWhenExplicitlyEnabled() {
        // The per-IP bucket is the only thing standing behind an
        // anonymous request, so a rejection from it proves the request
        // was admitted as anonymous traffic rather than turned away as
        // unauthenticated.
        when(ipRateLimitPort.tryConsume(anyString())).thenReturn(false);

        clientWithAnonymousAccess(true).post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request())
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectBody()
                .jsonPath("$.title").isEqualTo("ip_rate_limited");
    }
}
