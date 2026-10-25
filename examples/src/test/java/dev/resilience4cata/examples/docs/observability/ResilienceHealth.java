package dev.resilience4cata.examples.docs.observability;

// tag::observability-health[]
import dev.resilience4cata.CircuitBreakerSnapshot;
import dev.resilience4cata.CircuitBreakerState;
import dev.resilience4cata.ResilienceRegistry;
import java.util.List;

/** A readiness-style check: degraded while any circuit breaker rejects calls. */
public record ResilienceHealth(boolean healthy, List<String> openBreakers) {

    public static ResilienceHealth of(ResilienceRegistry registry) {
        List<String> open = registry.snapshot().circuitBreakers().stream()
                .filter(b -> b.state() == CircuitBreakerState.OPEN || b.state() == CircuitBreakerState.FORCED_OPEN)
                .map(CircuitBreakerSnapshot::instance)
                .toList();
        return new ResilienceHealth(open.isEmpty(), open);
    }
}
// end::observability-health[]
