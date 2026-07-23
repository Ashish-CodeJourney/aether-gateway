package com.aether.gateway.router;

import com.aether.gateway.core.domain.BreakerState;
import com.aether.gateway.core.domain.ChainMember;
import com.aether.gateway.core.domain.ProviderBreakerStatus;
import com.aether.gateway.core.port.BreakerStatusUseCase;
import com.aether.gateway.router.routing.RoutingPolicyRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

import java.util.List;
import java.util.Optional;

/** F3.8: breaker state observability, backing GET /admin/providers/{name}/breaker. */
public class RouterBreakerStatus implements BreakerStatusUseCase {

    private final RoutingPolicyRepository routingPolicyRepository;
    private final CircuitBreakerRegistry breakerRegistry;

    public RouterBreakerStatus(RoutingPolicyRepository routingPolicyRepository, CircuitBreakerRegistry breakerRegistry) {
        this.routingPolicyRepository = routingPolicyRepository;
        this.breakerRegistry = breakerRegistry;
    }

    @Override
    public List<ProviderBreakerStatus> allStatuses() {
        return routingPolicyRepository.allRoutes().values().stream()
                .flatMap(route -> route.chain().stream())
                .distinct()
                .map(this::toStatus)
                .toList();
    }

    @Override
    public Optional<ProviderBreakerStatus> statusFor(String provider) {
        return allStatuses().stream().filter(s -> s.provider().equals(provider)).findFirst();
    }

    private ProviderBreakerStatus toStatus(ChainMember member) {
        String key = member.provider() + ":" + member.model();
        CircuitBreaker breaker = breakerRegistry.circuitBreaker(key);
        // KNOWN LIMITATION, recorded honestly rather than papered over:
        // getState() alone never evaluates Resilience4j's lazy
        // OPEN -> HALF_OPEN transition, so this can report a stale OPEN
        // reading for a window after waitDurationInOpenState has elapsed
        // and the request-serving path (ResilientRouter, which always
        // goes through tryAcquirePermission()/executeSupplier()) has
        // already recovered. An earlier attempt to force the transition
        // here via tryAcquirePermission()+releasePermission() was
        // reverted: consuming a HALF_OPEN permit slot from a read-only
        // status check, without ever completing it via onSuccess/onError,
        // risks starving the limited permittedNumberOfCallsInHalfOpenState
        // budget real requests need to actually prove recovery. The
        // request-serving path's own behaviour (verified directly, see
        // STATUS.md) is unaffected by this endpoint's staleness; only
        // this observability read can lag briefly.
        return new ProviderBreakerStatus(member.provider(), member.model(), toDomainState(breaker.getState()));
    }

    private BreakerState toDomainState(CircuitBreaker.State state) {
        return switch (state) {
            case OPEN, FORCED_OPEN -> BreakerState.OPEN;
            case HALF_OPEN -> BreakerState.HALF_OPEN;
            default -> BreakerState.CLOSED;
        };
    }
}
