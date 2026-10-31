package dev.resilience4cata.quarkus.runtime;

import dev.resilience4cata.CircuitBreakerSnapshot;
import dev.resilience4cata.CircuitBreakerState;
import dev.resilience4cata.ResilienceRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Set;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;

/**
 * The {@code resilience4cata} readiness check: the state of every circuit breaker of the registry, read from
 * {@link ResilienceRegistry#snapshot()} when the check is queried, never on the call path. Registered only when
 * SmallRye Health is present and {@code quarkus.resilience4cata.health.enabled} is true.
 *
 * <p>{@code UP} by default even with open breakers: an open breaker means a dependency is failing and the
 * application is protecting itself from it, which is no reason to take it out of load balancing. Breakers listed in
 * {@code quarkus.resilience4cata.health.down-when-open} ({@code *} for all) turn it {@code DOWN} while open or
 * forced open. The data holds {@code <id>} = state and {@code <id>.failureRate}.
 */
@Readiness
@ApplicationScoped
public class ResilienceHealthCheck implements HealthCheck {

    private final ResilienceRegistry registry;
    private final Set<String> downWhenOpen;

    /**
     * Created by ArC.
     *
     * @param registry the registry to report
     * @param settings the run-time settings
     */
    @Inject
    public ResilienceHealthCheck(ResilienceRegistry registry, ResilienceRuntimeConfig settings) {
        this.registry = registry;
        this.downWhenOpen = Set.copyOf(breakerIds(settings.health().downWhenOpen().orElse(List.of())));
    }

    @Override
    public HealthCheckResponse call() {
        HealthCheckResponseBuilder response = HealthCheckResponse.named("resilience4cata");
        boolean up = true;
        boolean all = downWhenOpen.contains("*");
        for (CircuitBreakerSnapshot breaker : registry.snapshot().circuitBreakers()) {
            CircuitBreakerState state = breaker.state();
            response.withData(breaker.id(), state.name());
            response.withData(breaker.id() + ".failureRate", String.valueOf(breaker.failureRate()));
            if ((all || downWhenOpen.contains(breaker.id()))
                    && (state == CircuitBreakerState.OPEN || state == CircuitBreakerState.FORCED_OPEN)) {
                up = false;
            }
        }
        return response.status(up).build();
    }

    /**
     * The breaker ids of a list property: the framework splits a value at every comma, which also splits a derived id
     * such as {@code com.acme.Charges#charge(java.lang.String,int)}; parts are joined back while a parenthesis is open.
     */
    static List<String> breakerIds(List<String> parts) {
        List<String> ids = new java.util.ArrayList<>();
        StringBuilder pending = null;
        int open = 0;
        for (String part : parts) {
            pending = pending == null ? new StringBuilder(part.strip()) : pending.append(',').append(part.strip());
            open += (int) (part.chars().filter(c -> c == '(').count() - part.chars().filter(c -> c == ')').count());
            if (open <= 0) {
                ids.add(pending.toString());
                pending = null;
                open = 0;
            }
        }
        if (pending != null) {
            ids.add(pending.toString());
        }
        return ids.stream().filter(id -> !id.isEmpty()).toList();
    }
}
