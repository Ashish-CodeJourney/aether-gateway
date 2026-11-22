package com.aether.gateway.proxy.web;

import com.aether.gateway.core.domain.ApiKeyContext;
import com.aether.gateway.core.domain.Embedding;
import com.aether.gateway.core.domain.EmbeddingOutcome;
import com.aether.gateway.core.domain.EmbeddingRequest;
import com.aether.gateway.core.domain.EmbeddingResponse;
import com.aether.gateway.core.domain.ModelPricing;
import com.aether.gateway.core.domain.ProviderResponse;
import com.aether.gateway.core.domain.QuotaDecision;
import com.aether.gateway.core.domain.RequestLogEntry;
import com.aether.gateway.core.port.ApiKeyLookupPort;
import com.aether.gateway.core.port.CostModelPort;
import com.aether.gateway.core.port.EmbeddingUseCase;
import com.aether.gateway.core.port.IpRateLimitPort;
import com.aether.gateway.core.port.MetricsPort;
import com.aether.gateway.core.port.QuotaPort;
import com.aether.gateway.core.port.RequestLogPort;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmbeddingsControllerTest {

    private static final String KEY_ID = "3f0e6c1e-8a43-4c55-9d0e-0b6f1a2b3c4d";

    private final EmbeddingUseCase embeddingUseCase = mock(EmbeddingUseCase.class);
    private final ApiKeyLookupPort apiKeyLookupPort = mock(ApiKeyLookupPort.class);
    private final QuotaPort quotaPort = mock(QuotaPort.class);
    private final IpRateLimitPort ipRateLimitPort = mock(IpRateLimitPort.class);
    private final MetricsPort metricsPort = mock(MetricsPort.class);
    private final RequestLogPort requestLogPort = mock(RequestLogPort.class);
    private final CostModelPort costModelPort = mock(CostModelPort.class);

    private WebTestClient client(boolean allowAnonymous) {
        when(ipRateLimitPort.tryConsume(anyString())).thenReturn(true);
        var controller = new EmbeddingsController(
                embeddingUseCase,
                new RequestAdmission(apiKeyLookupPort, quotaPort, ipRateLimitPort, allowAnonymous),
                metricsPort,
                requestLogPort,
                costModelPort,
                Schedulers.immediate());
        return WebTestClient.bindToController(controller).build();
    }

    private WebTestClient anonymousClient() {
        return client(true);
    }

    private static EmbeddingOutcome.Success servedBy(String provider, List<Embedding> data, int promptTokens) {
        return new EmbeddingOutcome.Success(
                new EmbeddingResponse("upstream-embed", data, promptTokens), provider, "upstream-embed", 2, List.of("primary", provider));
    }

    private void providerReturns(EmbeddingOutcome outcome) {
        when(embeddingUseCase.embed(any())).thenReturn(outcome);
    }

    private WebTestClient.ResponseSpec post(WebTestClient client, String body) {
        return client.post().uri("/v1/embeddings")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange();
    }

    private EmbeddingRequest requestSentToTheUseCase() {
        var captor = ArgumentCaptor.forClass(EmbeddingRequest.class);
        verify(embeddingUseCase).embed(captor.capture());
        return captor.getValue();
    }

    @Test
    void returnsTheOpenAiEmbeddingListShapeForASingleStringInput() {
        providerReturns(servedBy("fallback", List.of(new Embedding(0, List.of(0.25f, -0.5f))), 3));

        post(anonymousClient(), """
                {"model": "embed", "input": "hello world"}
                """)
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.object").isEqualTo("list")
                .jsonPath("$.model").isEqualTo("upstream-embed")
                .jsonPath("$.data.length()").isEqualTo(1)
                .jsonPath("$.data[0].object").isEqualTo("embedding")
                .jsonPath("$.data[0].index").isEqualTo(0)
                .jsonPath("$.data[0].embedding[0]").isEqualTo(0.25)
                .jsonPath("$.data[0].embedding[1]").isEqualTo(-0.5)
                .jsonPath("$.usage.prompt_tokens").isEqualTo(3)
                .jsonPath("$.usage.total_tokens").isEqualTo(3);

        assertThat(requestSentToTheUseCase()).isEqualTo(new EmbeddingRequest("embed", List.of("hello world"), null));
    }

    @Test
    void forwardsEveryStringOfAnArrayInputAndTheRequestedDimensions() {
        providerReturns(servedBy("primary", List.of(
                new Embedding(0, List.of(0.1f)), new Embedding(1, List.of(0.2f))), 2));

        post(anonymousClient(), """
                {"model": "embed", "input": ["first", "second"], "dimensions": 64}
                """)
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.length()").isEqualTo(2)
                .jsonPath("$.data[1].index").isEqualTo(1);

        assertThat(requestSentToTheUseCase()).isEqualTo(new EmbeddingRequest("embed", List.of("first", "second"), 64));
    }

    @Test
    void reportsWhichProviderServedTheRequestHowManyAttemptsItTookAndWhatItCost() {
        providerReturns(servedBy("fallback", List.of(new Embedding(0, List.of(0.1f))), 2_000));
        when(costModelPort.pricingFor("fallback", "upstream-embed")).thenReturn(Optional.of(
                new ModelPricing("fallback", "upstream-embed", new BigDecimal("0.02"), BigDecimal.ZERO)));

        post(anonymousClient(), """
                {"model": "embed", "input": "hello"}
                """)
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Aether-Provider", "fallback")
                .expectHeader().valueEquals("X-Aether-Attempts", "2")
                .expectHeader().valueEquals("X-Aether-Cost-USD", "0.00004");
    }

    @Test
    void fallsBackToPricingKeyedByTheRouteAliasWhenTheUpstreamModelIsNotPriced() {
        providerReturns(servedBy("fallback", List.of(new Embedding(0, List.of(0.1f))), 1_000_000));
        when(costModelPort.pricingFor("fallback", "upstream-embed")).thenReturn(Optional.empty());
        when(costModelPort.pricingFor("fallback", "embed")).thenReturn(Optional.of(
                new ModelPricing("fallback", "embed", new BigDecimal("0.10"), BigDecimal.ZERO)));

        post(anonymousClient(), """
                {"model": "embed", "input": "hello"}
                """)
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Aether-Cost-USD", "0.1");
    }

    @Test
    void reportsZeroCostWhenTheModelIsNotInTheCostModel() {
        providerReturns(servedBy("fallback", List.of(new Embedding(0, List.of(0.1f))), 10));
        when(costModelPort.pricingFor(anyString(), anyString())).thenReturn(Optional.empty());

        post(anonymousClient(), """
                {"model": "embed", "input": "hello"}
                """)
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Aether-Cost-USD", "0");
    }

    @Test
    void encodesVectorsAsBase64LittleEndianFloat32WhenAsked() {
        providerReturns(servedBy("primary", List.of(new Embedding(0, List.of(1.5f, -2.0f))), 1));
        ByteBuffer expected = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putFloat(1.5f).putFloat(-2.0f);

        post(anonymousClient(), """
                {"model": "embed", "input": "hello", "encoding_format": "base64"}
                """)
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[0].embedding").isEqualTo(Base64.getEncoder().encodeToString(expected.array()));
    }

    @Test
    void acceptsAnExplicitFloatEncodingFormat() {
        providerReturns(servedBy("primary", List.of(new Embedding(0, List.of(1.5f))), 1));

        post(anonymousClient(), """
                {"model": "embed", "input": "hello", "encoding_format": "float"}
                """)
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[0].embedding[0]").isEqualTo(1.5);
    }

    @Test
    void rejectsAnUnknownEncodingFormat() {
        post(anonymousClient(), """
                {"model": "embed", "input": "hello", "encoding_format": "int8"}
                """)
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error.message").value(message -> assertThat((String) message).contains("encoding_format"))
                .jsonPath("$.error.type").isEqualTo("invalid_request_error")
                .jsonPath("$.error.param").isEqualTo("encoding_format");
        verify(embeddingUseCase, never()).embed(any());
    }

    @Test
    void rejectsTokenArrayInputWithAClearMessage() {
        post(anonymousClient(), """
                {"model": "embed", "input": [1820, 25977]}
                """)
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.title").isEqualTo("invalid_request")
                .jsonPath("$.error.param").isEqualTo("input")
                .jsonPath("$.error.message").value(message -> assertThat((String) message).contains("token"));
        verify(embeddingUseCase, never()).embed(any());
    }

    @Test
    void rejectsNestedTokenArrayInput() {
        post(anonymousClient(), """
                {"model": "embed", "input": [[1820, 25977], [42]]}
                """)
                .expectStatus().isBadRequest();
        verify(embeddingUseCase, never()).embed(any());
    }

    @Test
    void rejectsAMissingInput() {
        post(anonymousClient(), """
                {"model": "embed"}
                """)
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error.param").isEqualTo("input");
    }

    @Test
    void rejectsAMissingModel() {
        post(anonymousClient(), """
                {"input": "hello"}
                """)
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error.message").value(message -> assertThat((String) message).contains("model"));
    }

    @Test
    void rejectsNonPositiveDimensions() {
        post(anonymousClient(), """
                {"model": "embed", "input": "hello", "dimensions": 0}
                """)
                .expectStatus().isBadRequest();
        verify(embeddingUseCase, never()).embed(any());
    }

    @Test
    void mapsAProviderFailureToItsStatusWithAnOpenAiStyleErrorBody() {
        providerReturns(new EmbeddingOutcome.Failure(new ProviderResponse.ProviderError(
                EmbeddingOutcome.EMBEDDINGS_NOT_SUPPORTED, "Route embed has no provider that supports embeddings",
                400, false, 1, List.of("anthropic-main"))));

        post(anonymousClient(), """
                {"model": "embed", "input": "hello"}
                """)
                .expectStatus().isBadRequest()
                .expectHeader().valueEquals("X-Aether-Attempts", "1")
                .expectBody()
                .jsonPath("$.title").isEqualTo(EmbeddingOutcome.EMBEDDINGS_NOT_SUPPORTED)
                .jsonPath("$.instance").isEqualTo("/v1/embeddings")
                .jsonPath("$.error.code").isEqualTo(EmbeddingOutcome.EMBEDDINGS_NOT_SUPPORTED)
                .jsonPath("$.error.type").isEqualTo("invalid_request_error")
                .jsonPath("$.error.message").isEqualTo("Route embed has no provider that supports embeddings");
    }

    @Test
    void reportsAnUpstreamOutageAsAServerSideErrorType() {
        providerReturns(new EmbeddingOutcome.Failure(new ProviderResponse.ProviderError(
                "no_healthy_provider", "All providers unavailable", 502, false, 2, List.of("a", "b"))));

        post(anonymousClient(), """
                {"model": "embed", "input": "hello"}
                """)
                .expectStatus().isEqualTo(502)
                .expectBody()
                .jsonPath("$.error.type").isEqualTo("api_error");
    }

    @Test
    void rejectsAnUnauthenticatedRequestWhenAnonymousAccessIsOff() {
        post(client(false), """
                {"model": "embed", "input": "hello"}
                """)
                .expectStatus().isUnauthorized()
                .expectHeader().exists("WWW-Authenticate")
                .expectBody()
                .jsonPath("$.title").isEqualTo("unauthenticated");
        verify(embeddingUseCase, never()).embed(any());
    }

    @Test
    void reservesTheEstimatedInputTokensAgainstTheKeysQuotaThenReconcilesToActualUsage() {
        var key = new ApiKeyContext(KEY_ID, 10, 5, 1_000L);
        when(apiKeyLookupPort.findByRawKey("aeth_real")).thenReturn(Optional.of(key));
        when(quotaPort.checkAndReserve(eq(key), anyString(), anyLong()))
                .thenReturn(new QuotaDecision.Allowed("req-1", 990, false));
        providerReturns(servedBy("primary", List.of(new Embedding(0, List.of(0.1f))), 7));

        client(false).post().uri("/v1/embeddings")
                .header("Authorization", "Bearer aeth_real")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model": "embed", "input": "abcdefgh"}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Aether-Quota-Remaining", "990");

        verify(quotaPort).checkAndReserve(eq(key), anyString(), eq(2L));
        verify(quotaPort).reconcile(KEY_ID, "req-1", 7);
        verify(quotaPort).releaseConcurrencySlot(KEY_ID, "req-1");
    }

    @Test
    void releasesTheReservationWhenTheProviderFails() {
        var key = new ApiKeyContext(KEY_ID, 10, 5, 1_000L);
        when(apiKeyLookupPort.findByRawKey("aeth_real")).thenReturn(Optional.of(key));
        when(quotaPort.checkAndReserve(eq(key), anyString(), anyLong()))
                .thenReturn(new QuotaDecision.Allowed("req-1", 990, false));
        providerReturns(new EmbeddingOutcome.Failure(new ProviderResponse.ProviderError("provider_error", "boom", 500, false)));

        client(false).post().uri("/v1/embeddings")
                .header("Authorization", "Bearer aeth_real")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model": "embed", "input": "hello"}
                        """)
                .exchange()
                .expectStatus().isEqualTo(500);

        verify(quotaPort).reconcile(KEY_ID, "req-1", 0);
        verify(quotaPort).releaseConcurrencySlot(KEY_ID, "req-1");
    }

    @Test
    void refusesARequestOverTheKeysQuotaWithoutCallingAProvider() {
        var key = new ApiKeyContext(KEY_ID, 10, 5, 1_000L);
        when(apiKeyLookupPort.findByRawKey("aeth_real")).thenReturn(Optional.of(key));
        when(quotaPort.checkAndReserve(eq(key), anyString(), anyLong()))
                .thenReturn(new QuotaDecision.Rejected("budget_exceeded", 0));

        client(false).post().uri("/v1/embeddings")
                .header("Authorization", "Bearer aeth_real")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model": "embed", "input": "hello"}
                        """)
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectBody()
                .jsonPath("$.title").isEqualTo("budget_exceeded");

        verify(embeddingUseCase, never()).embed(any());
    }

    @Test
    void writesARequestLogEntryAttributedToTheServingProvider() {
        providerReturns(servedBy("fallback", List.of(new Embedding(0, List.of(0.1f))), 5));
        when(costModelPort.pricingFor(anyString(), anyString())).thenReturn(Optional.empty());

        post(anonymousClient(), """
                {"model": "embed", "input": "hello"}
                """)
                .expectStatus().isOk();

        var captor = ArgumentCaptor.forClass(RequestLogEntry.class);
        verify(requestLogPort).log(captor.capture());
        RequestLogEntry entry = captor.getValue();
        assertThat(entry.routeAlias()).isEqualTo("embed");
        assertThat(entry.provider()).isEqualTo("fallback");
        assertThat(entry.model()).isEqualTo("upstream-embed");
        assertThat(entry.inputTokens()).isEqualTo(5);
        assertThat(entry.outputTokens()).isZero();
        assertThat(entry.streamed()).isFalse();
        assertThat(entry.cacheOutcome()).isEqualTo("BYPASS");
        assertThat(entry.status()).isEqualTo("OK");
        assertThat(entry.attemptCount()).isEqualTo(2);
        assertThat(entry.failoverChain()).containsExactly("primary", "fallback");
    }
}
