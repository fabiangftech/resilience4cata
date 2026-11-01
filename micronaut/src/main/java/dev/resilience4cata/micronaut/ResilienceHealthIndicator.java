package dev.resilience4cata.micronaut;

import dev.resilience4cata.CircuitBreakerSnapshot;
import dev.resilience4cata.CircuitBreakerState;
import dev.resilience4cata.ResilienceRegistry;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.health.HealthStatus;
import io.micronaut.management.endpoint.health.HealthEndpoint;
import io.micronaut.management.health.indicator.HealthIndicator;
import io.micronaut.management.health.indicator.HealthResult;
import jakarta.inject.Singleton;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.reactivestreams.Publisher;

/**
 * Reports every circuit breaker of a {@link ResilienceRegistry}: its state, failure and slow-call rates and the
 * calls in its window, read from {@link ResilienceRegistry#snapshot()} when the health endpoint is queried, never
 * on the call path. Registered as the {@code resilience4cata} indicator when {@code micronaut-management} is on the
 * class path; {@code resilience4cata.micronaut.health.enabled=false} removes it.
 *
 * <p>The status is {@code UP} by default even with open breakers: an open breaker means a dependency is failing
 * and the application is protecting itself from it, which is no reason to restart the application or take it out
 * of load balancing. Breakers listed in {@code resilience4cata.micronaut.health.down-when-open} ({@code *} for all)
 * turn the status {@code DOWN} while open or forced open, and {@code UNKNOWN} while half open. It is neither a
 * liveness nor a readiness indicator, so it counts only in {@code /health}.
 */
@Singleton
@Requires(classes = HealthIndicator.class)
@Requires(beans = HealthEndpoint.class)
@Requires(property = MicronautSettings.PREFIX + ".health.enabled", notEquals = "false")
public final class ResilienceHealthIndicator implements HealthIndicator {

    /** The indicator's name in the health report: {@value}. */
    public static final String NAME = "resilience4cata";

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

    @jakarta.inject.Inject
    ResilienceHealthIndicator(ResilienceRegistry registry, io.micronaut.context.env.Environment environment) {
        this(registry, MicronautSettings.of(environment).downWhenOpen());
    }

    @Override
    public Publisher<HealthResult> getResult() {
        return Publishers.just(health());
    }

    /**
     * The current result, computed from a snapshot of the registry.
     *
     * @return the health result
     */
    public HealthResult health() {
        HealthStatus status = HealthStatus.UP;
        Map<String, Object> breakers = new LinkedHashMap<>();
        for (CircuitBreakerSnapshot breaker : registry.snapshot().circuitBreakers()) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("state", breaker.state().name());
            details.put("failureRate", finite(breaker.failureRate()));
            details.put("slowCallRate", finite(breaker.slowCallRate()));
            details.put("bufferedCalls", breaker.bufferedCalls());
            details.put("failedCalls", breaker.failedCalls());
            details.put("slowCalls", breaker.slowCalls());
            breakers.put(breaker.id(), details);
            if (all || downWhenOpen.contains(breaker.id())) {
                CircuitBreakerState state = breaker.state();
                if (state == CircuitBreakerState.OPEN || state == CircuitBreakerState.FORCED_OPEN) {
                    status = HealthStatus.DOWN;
                } else if (state == CircuitBreakerState.HALF_OPEN && status != HealthStatus.DOWN) {
                    status = HealthStatus.UNKNOWN;
                }
            }
        }
        return HealthResult.builder(NAME, status).details(Map.of("circuitBreakers", breakers)).build();
    }

    private static Object finite(double rate) {
        return Double.isFinite(rate) ? rate : "n/a";
    }
}
