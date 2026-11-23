package com.aether.gateway.observability;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CostModelRepositoryTest {

    private static String pricing(String inputPrice) {
        return """
                models:
                  - provider: anthropic-main
                    model: claude-haiku-5-5
                    inputPricePerMillion: %s
                    outputPricePerMillion: 0.50
                """.formatted(inputPrice);
    }

    @Test
    void picksUpEditedPricesOnReload(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("cost-model.yaml"), pricing("0.10"));
        var repository = new CostModelRepository(file, new CostModelYamlParser());

        Files.writeString(file, pricing("0.20"));
        repository.reload();

        assertThat(repository.pricingFor("anthropic-main", "claude-haiku-5-5"))
                .hasValueSatisfying(p -> assertThat(p.inputPricePerMillion()).isEqualByComparingTo(new BigDecimal("0.20")));
    }

    @Test
    void rejectsAnInvalidCostModelAndKeepsServingTheLastGoodPrices(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("cost-model.yaml"), pricing("0.10"));
        var repository = new CostModelRepository(file, new CostModelYamlParser());

        Files.writeString(file, pricing("not-a-number"));

        assertThatThrownBy(repository::reload)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cost-model.yaml");
        assertThat(repository.pricingFor("anthropic-main", "claude-haiku-5-5"))
                .hasValueSatisfying(p -> assertThat(p.inputPricePerMillion()).isEqualByComparingTo(new BigDecimal("0.10")));
    }

    @Test
    void rejectsMalformedYaml(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("cost-model.yaml"), pricing("0.10"));
        var repository = new CostModelRepository(file, new CostModelYamlParser());

        Files.writeString(file, "models: [ {provider: x, model: ");

        assertThatThrownBy(repository::reload)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cost-model.yaml");
    }
}
