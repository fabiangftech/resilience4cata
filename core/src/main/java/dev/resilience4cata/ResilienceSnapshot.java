package dev.resilience4cata;

import java.util.List;

/**
 * Read-only view of every policy instance of a registry at one moment, for health checks, admin
 * endpoints and metrics. Returned by {@link ResilienceRegistry#snapshot()}; each list is sorted by
 * instance id. Instances exist once their interface has been wrapped with {@code Resilient.of(...)}.
 *
 * <p>Values are read without stopping the calls in flight, so they can lag behind by a few calls and
 * are not taken at exactly the same instant; each is clamped to its valid range.
 *
 * @param circuitBreakers every circuit breaker
 * @param retries every retry
 * @param rateLimiters every rate limiter
 * @param timeouts every timeout
 * @param bulkheads every bulkhead
 */
public record ResilienceSnapshot(List<CircuitBreakerSnapshot> circuitBreakers, List<RetrySnapshot> retries,
        List<RateLimiterSnapshot> rateLimiters, List<TimeoutSnapshot> timeouts, List<BulkheadSnapshot> bulkheads) {

    /** Copies the lists. */
    public ResilienceSnapshot {
        circuitBreakers = List.copyOf(circuitBreakers);
        retries = List.copyOf(retries);
        rateLimiters = List.copyOf(rateLimiters);
        timeouts = List.copyOf(timeouts);
        bulkheads = List.copyOf(bulkheads);
    }
}
