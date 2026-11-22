package com.aether.gateway.mockprovider.web;

import com.aether.gateway.mockprovider.DefaultControlsHolder;
import com.aether.gateway.mockprovider.DeterministicEmbeddingGenerator;
import com.aether.gateway.mockprovider.MockControls;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

class MockEmbeddingsControllerTest {

    private final DefaultControlsHolder defaults = new DefaultControlsHolder();
    private final WebTestClient client = WebTestClient
            .bindToController(new MockEmbeddingsController(defaults))
            .build();

    @Test
    void servesOneDeterministicEmbeddingPerInputInTheOpenAiShape() {
        float first = new DeterministicEmbeddingGenerator().embed("hello", 4).get(0);

        client.post().uri("/v1/embeddings")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model": "mock", "input": ["hello", "world"], "dimensions": 4}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.object").isEqualTo("list")
                .jsonPath("$.model").isEqualTo("mock")
                .jsonPath("$.data.length()").isEqualTo(2)
                .jsonPath("$.data[0].object").isEqualTo("embedding")
                .jsonPath("$.data[1].index").isEqualTo(1)
                .jsonPath("$.data[0].embedding.length()").isEqualTo(4)
                .jsonPath("$.data[0].embedding[0]").isEqualTo(Double.parseDouble(Float.toString(first)))
                .jsonPath("$.usage.prompt_tokens").isEqualTo(2)
                .jsonPath("$.usage.total_tokens").isEqualTo(2);
    }

    @Test
    void acceptsASingleStringInput() {
        client.post().uri("/v1/embeddings")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model": "mock", "input": "hello"}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.length()").isEqualTo(1);
    }

    @Test
    void appliesTheConfiguredDefaultFailureMode() {
        defaults.set(new MockControls(null, "503", 1.0, null, null, null));

        client.post().uri("/v1/embeddings")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model": "mock", "input": "hello"}
                        """)
                .exchange()
                .expectStatus().isEqualTo(503);
    }

    @Test
    void honoursThePerRequestFailureHeader() {
        client.post().uri("/v1/embeddings")
                .header("X-Mock-Fail", "429")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model": "mock", "input": "hello"}
                        """)
                .exchange()
                .expectStatus().isEqualTo(429);
    }
}
