package com.aether.gateway.core.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * F2.5: weighted load balancing among a route's weighted chain members,
 * F2.3: unweighted members are strict order-only fallbacks, tried after
 * every weighted member. Pure domain logic, no I/O, no breaker
 * awareness; the router (gateway-router) walks the returned order and
 * skips members whose breaker is currently open at invocation time.
 */
public class RouteResolver {

    /** Weighted random sampling without replacement: higher weight sorts first more often, never deterministically. */
    public List<ChainMember> weightedOrder(List<ChainMember> members, Random random) {
        List<ChainMember> pool = new ArrayList<>(members);
        List<ChainMember> result = new ArrayList<>(members.size());

        while (!pool.isEmpty()) {
            int totalWeight = pool.stream().mapToInt(m -> Math.max(1, m.weight())).sum();
            int pick = random.nextInt(totalWeight);
            int cumulative = 0;
            int index = 0;
            for (int i = 0; i < pool.size(); i++) {
                cumulative += Math.max(1, pool.get(i).weight());
                if (pick < cumulative) {
                    index = i;
                    break;
                }
            }
            result.add(pool.remove(index));
        }
        return result;
    }

    public List<ChainMember> resolveAttemptOrder(RouteConfig route, Random random) {
        List<ChainMember> weighted = route.chain().stream().filter(m -> m.weight() != null).toList();
        List<ChainMember> fallbacks = route.chain().stream().filter(m -> m.weight() == null).toList();

        List<ChainMember> order = new ArrayList<>(weightedOrder(weighted, random));
        order.addAll(fallbacks);
        return order;
    }
}
