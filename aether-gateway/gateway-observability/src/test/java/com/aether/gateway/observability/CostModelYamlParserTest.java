package com.aether.gateway.observability;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class CostModelYamlParserTest {

    private final CostModelYamlParser parser = new CostModelYamlParser();

    @Test
    void parsesAModelEntry() {
        String yaml = """
                models:
                  - provider: mock
                    model: mock
                    inputPricePerMillion: 0.50
                    outputPricePerMillion: 1.50
                """;

        var pricingByKey = parser.parse(yaml);

        var pricing = pricingByKey.get("mock:mock");
        assertThat(pricing.provider()).isEqualTo("mock");
        assertThat(pricing.model()).isEqualTo("mock");
        assertThat(pricing.inputPricePerMillion()).isEqualByComparingTo(new BigDecimal("0.50"));
        assertThat(pricing.outputPricePerMillion()).isEqualByComparingTo(new BigDecimal("1.50"));
    }

    @Test
    void parsesMultipleModels() {
        String yaml = """
                models:
                  - provider: mock
                    model: mock
                    inputPricePerMillion: 0.50
                    outputPricePerMillion: 1.50
                  - provider: groq
                    model: llama-3.3-70b-versatile
                    inputPricePerMillion: 0.59
                    outputPricePerMillion: 0.79
                """;

        var pricingByKey = parser.parse(yaml);

        assertThat(pricingByKey).containsOnlyKeys("mock:mock", "groq:llama-3.3-70b-versatile");
    }

    @Test
    void emptyModelsProducesAnEmptyMapNotAnError() {
        String yaml = "models: []";

        var pricingByKey = parser.parse(yaml);

        assertThat(pricingByKey).isEmpty();
    }
}
