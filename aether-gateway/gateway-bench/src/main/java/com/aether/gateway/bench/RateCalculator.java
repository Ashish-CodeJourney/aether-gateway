package com.aether.gateway.bench;

import java.util.List;
import java.util.function.Predicate;

/** Pure proportion-matching used by every experiment (hit rate, false-hit rate, recall@k, ...). */
public final class RateCalculator {

    private RateCalculator() {
    }

    public static <T> double fraction(List<T> items, Predicate<T> predicate) {
        if (items.isEmpty()) {
            throw new IllegalArgumentException("Cannot compute a fraction of an empty list");
        }
        long matching = items.stream().filter(predicate).count();
        return (double) matching / items.size();
    }
}
