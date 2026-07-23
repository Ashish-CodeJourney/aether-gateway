package com.aether.gateway.core.domain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class RouteResolverTest {

    private final RouteResolver resolver = new RouteResolver();

    @Test
    void aSingleWeightedMemberIsAlwaysFirst() {
        var member = new ChainMember("groq", "llama", 100);

        var order = resolver.weightedOrder(List.of(member), new Random(1));

        assertThat(order).containsExactly(member);
    }

    @Test
    void everyMemberAppearsExactlyOnceRegardlessOfWeight() {
        var a = new ChainMember("groq", "llama", 80);
        var b = new ChainMember("gemini", "flash", 20);
        var c = new ChainMember("ollama", "llama3.2", 1);

        var order = resolver.weightedOrder(List.of(a, b, c), new Random(42));

        assertThat(order).containsExactlyInAnyOrder(a, b, c);
        assertThat(order).hasSize(3);
    }

    @Test
    void higherWeightIsPickedFirstMoreOftenOverManyTrials() {
        var heavy = new ChainMember("groq", "llama", 95);
        var light = new ChainMember("ollama", "llama3.2", 5);

        int heavyFirstCount = 0;
        int trials = 2000;
        for (int i = 0; i < trials; i++) {
            var order = resolver.weightedOrder(List.of(heavy, light), new Random(i));
            if (order.get(0).equals(heavy)) {
                heavyFirstCount++;
            }
        }

        double observedRate = (double) heavyFirstCount / trials;
        assertThat(observedRate).isGreaterThan(0.85);
    }

    @Test
    void emptyInputProducesEmptyOrder() {
        assertThat(resolver.weightedOrder(List.of(), new Random(1))).isEmpty();
    }

    @Test
    void resolveAttemptOrderPutsWeightedMembersBeforeUnweightedFallbacks() {
        var weighted = new ChainMember("groq", "llama", 100);
        var fallback = new ChainMember("ollama", "llama3.2", null);
        var route = new RouteConfig("fast-chat", List.of(weighted, fallback));

        var order = resolver.resolveAttemptOrder(route, new Random(1));

        assertThat(order).containsExactly(weighted, fallback);
    }

    @Test
    void resolveAttemptOrderPreservesRelativeOrderOfMultipleFallbacks() {
        var fallback1 = new ChainMember("ollama", "llama3.2", null);
        var fallback2 = new ChainMember("groq", "llama", null);
        var route = new RouteConfig("fast-chat", List.of(fallback1, fallback2));

        var order = resolver.resolveAttemptOrder(route, new Random(1));

        assertThat(order).containsExactly(fallback1, fallback2);
    }

    @Test
    void weightedOrderNeverMutatesTheInputList() {
        var a = new ChainMember("groq", "llama", 80);
        var b = new ChainMember("gemini", "flash", 20);
        List<ChainMember> input = new ArrayList<>(List.of(a, b));

        resolver.weightedOrder(input, new Random(1));

        assertThat(input).containsExactly(a, b);
    }
}
