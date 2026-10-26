package dev.resilience4cata.spring;

import dev.resilience4cata.CircuitBreakerSnapshot;
import dev.resilience4cata.CircuitBreakerState;
import dev.resilience4cata.ResilienceRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;

/**
 * Reports every circuit breaker of a {@link ResilienceRegistry}: its state, failure and slow-call rates and
 * the calls in its window, read from {@link ResilienceRegistry#snapshot()} when the health endpoint is
 * queried, never on the call path. Registered as the {@code resilience4cata} health contributor.
 *
 * <p>The status is {@code UP} by default even with open breakers: an open breaker means a dependency is
 * failing and the application is protecting itself from it, which is no reason to restart the
 * application or take it out of load balancing. Breakers listed in
 * {@code resilience4cata.spring.health.down-when-open} ({@code *} for all) turn the status {@code DOWN}
 * while open or forced open, and {@code UNKNOWN} while half open.
 */
public final class ResilienceHealthIndicator implements HealthIndicator {

    private final ResilienceRegistry registry;
    private final Set<String> downWhenOpen;
    private final boolean all;

    /**
     * Creates the indicator.
     *
     * @param registry the registry to report
     * @param downWhenOpen the breaker ids that are critical, or {@code *} for all
     */
    public ResilienceHealthIndicator(ResilienceRegistry registry, List<String> downWhenOpen) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.downWhenOpen = Set.copyOf(downWhenOpen);
        this.all = this.downWhenOpen.contains("*");
    }

    @Override
    public Health health() {
        Status status = Status.UP;
        Map<String, Object> breakers = new LinkedHashMap<>();
        for (CircuitBreakerSnapshot breaker : registry.snapshot().circuitBreakers()) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("state", breaker.state());
            details.put("failureRate", breaker.failureRate());
            details.put("slowCallRate", breaker.slowCallRate());
            details.put("bufferedCalls", breaker.bufferedCalls());
            details.put("failedCalls", breaker.failedCalls());
            details.put("slowCalls", breaker.slowCalls());
            breakers.put(breaker.instance(), details);
            if (all || downWhenOpen.contains(breaker.instance())) {
                CircuitBreakerState state = breaker.state();
                if (state == CircuitBreakerState.OPEN || state == CircuitBreakerState.FORCED_OPEN) {
                    status = Status.DOWN;
                } else if (state == CircuitBreakerState.HALF_OPEN && status != Status.DOWN) {
                    status = Status.UNKNOWN;
                }
            }
        }
        return Health.status(status).withDetail("circuitBreakers", breakers).build();
    }
}
