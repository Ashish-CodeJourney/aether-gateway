package com.aether.gateway.mockprovider;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultControlsHolderTest {

    @Test
    void hasNoDefaultFailureUntilConfigured() {
        var holder = new DefaultControlsHolder();

        assertThat(holder.current()).isEqualTo(MockControls.none());
    }

    @Test
    void storesAConfiguredDefault() {
        var holder = new DefaultControlsHolder();
        var configured = new MockControls(null, "503", 1.0, null, null, null);

        holder.set(configured);

        assertThat(holder.current()).isEqualTo(configured);
    }

    @Test
    void resetReturnsToNoFailureDefault() {
        var holder = new DefaultControlsHolder();
        holder.set(new MockControls(null, "503", 1.0, null, null, null));

        holder.reset();

        assertThat(holder.current()).isEqualTo(MockControls.none());
    }

    @Test
    void perRequestHeadersOverrideTheConfiguredDefaultWhenPresent() {
        var holder = new DefaultControlsHolder();
        holder.set(new MockControls(null, "503", 1.0, null, null, null));
        var perRequest = new MockControls(null, "429", null, null, null, null);

        MockControls effective = holder.resolve(perRequest);

        assertThat(effective).isEqualTo(perRequest);
    }

    @Test
    void fallsBackToTheConfiguredDefaultWhenTheRequestCarriesNoFailHeader() {
        var holder = new DefaultControlsHolder();
        var configuredDefault = new MockControls(null, "503", 1.0, null, null, null);
        holder.set(configuredDefault);
        var perRequestWithNoFailMode = new MockControls(200L, null, null, null, null, 5);

        MockControls effective = holder.resolve(perRequestWithNoFailMode);

        assertThat(effective.failMode()).isEqualTo("503");
        assertThat(effective.failRate()).isEqualTo(1.0);
        // non-fault fields from the per-request headers are preserved
        assertThat(effective.latencyMs()).isEqualTo(200L);
        assertThat(effective.tokens()).isEqualTo(5);
    }
}
