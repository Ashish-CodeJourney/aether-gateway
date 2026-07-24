package com.aether.gateway.bench;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Groups seed/variant rows of a paired corpus bucket (near-duplicates, adversarial) by `pair_id`. */
public final class PairGrouper {

    private PairGrouper() {
    }

    public static List<CorpusPair> group(List<BenchmarkCorpusEntry> entries) {
        Map<String, String> seeds = new LinkedHashMap<>();
        Map<String, String> variants = new LinkedHashMap<>();
        List<String> order = new ArrayList<>();

        for (BenchmarkCorpusEntry entry : entries) {
            if (entry.pairId() == null) {
                continue;
            }
            if ("seed".equals(entry.role())) {
                seeds.put(entry.pairId(), entry.prompt());
                order.add(entry.pairId());
            } else if ("variant".equals(entry.role())) {
                variants.put(entry.pairId(), entry.prompt());
            }
        }

        List<CorpusPair> pairs = new ArrayList<>();
        for (String pairId : order) {
            if (variants.containsKey(pairId)) {
                pairs.add(new CorpusPair(pairId, seeds.get(pairId), variants.get(pairId)));
            }
        }
        return pairs;
    }
}
