package dev.resilience4cata.examples.docs.observability;

// tag::observability-gauges[]
import dev.resilience4cata.BulkheadHandle;
import dev.resilience4cata.BulkheadSnapshot;
import dev.resilience4cata.CircuitBreakerHandle;
import dev.resilience4cata.CircuitBreakerState;
import dev.resilience4cata.RateLimiterHandle;
import dev.resilience4cata.RateLimiterSnapshot;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.ResilienceSnapshot;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;

/**
 * Micrometer gauges read from the registry's handles when the meters are scraped. Your code, not a
 * library module: copy it. Call bind(...) after wrapping your services: an instance exists only then.
 */
public final class ResilienceGauges {

    private ResilienceGauges() {
    }

    public static void bind(ResilienceRegistry registry, MeterRegistry meters) {
        for (CircuitBreakerHandle breaker : registry.circuitBreakers()) {
            for (CircuitBreakerState state : CircuitBreakerState.values()) { // 1 for the current state, else 0
                Gauge.builder("r4c.circuitbreaker.state", breaker, b -> b.state() == state ? 1 : 0)
                        .tag("instance", breaker.id()).tag("state", state.name().toLowerCase(Locale.ROOT))
                        .strongReference(true).register(meters);
            }
            Gauge.builder("r4c.circuitbreaker.failure.rate", breaker, b -> percent(b.snapshot().failureRate()))
                    .tag("instance", breaker.id()).baseUnit("percent").strongReference(true).register(meters);
            Gauge.builder("r4c.circuitbreaker.slow.call.rate", breaker, b -> percent(b.snapshot().slowCallRate()))
                    .tag("instance", breaker.id()).baseUnit("percent").strongReference(true).register(meters);
        }
        ResilienceSnapshot snapshot = registry.snapshot(); // lists every bulkhead and rate limiter
        for (BulkheadSnapshot b : snapshot.bulkheads()) {
            BulkheadHandle bulkhead = registry.bulkhead(b.instance());
            Gauge.builder("r4c.bulkhead.concurrent.calls", bulkhead, BulkheadHandle::concurrentCalls)
                    .tag("instance", b.instance()).strongReference(true).register(meters);
            Gauge.builder("r4c.bulkhead.waiting.calls", bulkhead, BulkheadHandle::waitingCalls)
                    .tag("instance", b.instance()).strongReference(true).register(meters);
            Gauge.builder("r4c.bulkhead.max.concurrent", bulkhead, h -> h.config().maxConcurrent())
                    .tag("instance", b.instance()).strongReference(true).register(meters);
        }
        for (RateLimiterSnapshot r : snapshot.rateLimiters()) {
            RateLimiterHandle limiter = registry.rateLimiter(r.instance());
            Gauge.builder("r4c.ratelimiter.available.permits", limiter, RateLimiterHandle::availablePermits)
                    .tag("instance", r.instance()).strongReference(true).register(meters);
        }
    }

    /** The snapshot reports -1 until the window holds minimumCalls calls: no value yet. */
    private static double percent(float rate) {
        return rate < 0 ? Double.NaN : rate;
    }
}
// end::observability-gauges[]
