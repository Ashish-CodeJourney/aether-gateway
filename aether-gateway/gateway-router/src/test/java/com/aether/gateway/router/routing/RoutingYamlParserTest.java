package com.aether.gateway.router.routing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RoutingYamlParserTest {

    private final RoutingYamlParser parser = new RoutingYamlParser();

    @Test
    void parsesProviderBaseUrls() {
        String yaml = """
                providers:
                  mock-primary:
                    baseUrl: http://localhost:8082
                  mock-fallback:
                    baseUrl: http://localhost:8083
                routes: []
                """;

        LoadedRoutingConfig config = parser.parse(yaml);

        assertThat(config.providers()).containsOnlyKeys("mock-primary", "mock-fallback");
        assertThat(config.providers().get("mock-primary").baseUrl()).isEqualTo("http://localhost:8082");
    }

    @Test
    void parsesARouteWithAWeightedChainAndAnUnweightedFallback() {
        String yaml = """
                providers:
                  mock-primary:
                    baseUrl: http://localhost:8082
                  mock-fallback:
                    baseUrl: http://localhost:8083
                routes:
                  - alias: fast-chat
                    chain:
                      - provider: mock-primary
                        model: mock
                        weight: 100
                      - provider: mock-fallback
                        model: mock
                """;

        LoadedRoutingConfig config = parser.parse(yaml);

        assertThat(config.routes()).containsOnlyKeys("fast-chat");
        var route = config.routes().get("fast-chat");
        assertThat(route.chain()).hasSize(2);
        assertThat(route.chain().get(0).provider()).isEqualTo("mock-primary");
        assertThat(route.chain().get(0).weight()).isEqualTo(100);
        assertThat(route.chain().get(1).provider()).isEqualTo("mock-fallback");
        assertThat(route.chain().get(1).weight()).isNull();
    }

    @Test
    void parsesMultipleRoutes() {
        String yaml = """
                providers:
                  mock-primary:
                    baseUrl: http://localhost:8082
                routes:
                  - alias: fast-chat
                    chain:
                      - provider: mock-primary
                        model: mock
                        weight: 100
                  - alias: creative-chat
                    chain:
                      - provider: mock-primary
                        model: mock
                        weight: 100
                """;

        LoadedRoutingConfig config = parser.parse(yaml);

        assertThat(config.routes()).containsOnlyKeys("fast-chat", "creative-chat");
    }

    @Test
    void emptyRoutesProducesAnEmptyRouteMapNotAnError() {
        String yaml = """
                providers: {}
                routes: []
                """;

        LoadedRoutingConfig config = parser.parse(yaml);

        assertThat(config.routes()).isEmpty();
        assertThat(config.providers()).isEmpty();
    }
}
