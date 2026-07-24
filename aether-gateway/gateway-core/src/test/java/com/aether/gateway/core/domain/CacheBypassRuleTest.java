package com.aether.gateway.core.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** F4.5: bypass cache when temperature > 0.3, tools are present, or X-Aether-No-Cache is set. */
class CacheBypassRuleTest {

    private final ChatCompletionRequest plainRequest =
            new ChatCompletionRequest("mock", List.of(new ChatMessage("user", "hi")), false, null, null);

    @Test
    void doesNotBypassAPlainRequest() {
        Optional<String> reason = CacheBypassRule.bypassReason(plainRequest, false);

        assertThat(reason).isEmpty();
    }

    @Test
    void bypassesWhenTheNoCacheHeaderIsPresent() {
        Optional<String> reason = CacheBypassRule.bypassReason(plainRequest, true);

        assertThat(reason).hasValue("no_cache_header");
    }

    @Test
    void bypassesWhenTemperatureExceedsPoint3() {
        var request = new ChatCompletionRequest("mock", List.of(new ChatMessage("user", "hi")), false, 0.31, null);

        Optional<String> reason = CacheBypassRule.bypassReason(request, false);

        assertThat(reason).hasValue("temperature_too_high");
    }

    @Test
    void doesNotBypassAtExactlyPoint3() {
        var request = new ChatCompletionRequest("mock", List.of(new ChatMessage("user", "hi")), false, 0.3, null);

        Optional<String> reason = CacheBypassRule.bypassReason(request, false);

        assertThat(reason).isEmpty();
    }

    @Test
    void bypassesWhenToolsArePresent() {
        var request = new ChatCompletionRequest(
                "mock", List.of(new ChatMessage("user", "hi")), false, null, List.of("get_weather"));

        Optional<String> reason = CacheBypassRule.bypassReason(request, false);

        assertThat(reason).hasValue("tools_present");
    }

    @Test
    void theNoCacheHeaderTakesPriorityWhenMultipleReasonsApply() {
        var request = new ChatCompletionRequest(
                "mock", List.of(new ChatMessage("user", "hi")), false, 0.9, List.of("get_weather"));

        Optional<String> reason = CacheBypassRule.bypassReason(request, true);

        assertThat(reason).hasValue("no_cache_header");
    }
}
