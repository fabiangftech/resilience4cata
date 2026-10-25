package dev.resilience4cata.examples.docs.observability;

// tag::observability-counters[]
import dev.resilience4cata.event.BulkheadEvent;
import dev.resilience4cata.event.CircuitBreakerEvent;
import dev.resilience4cata.event.EventListener;
import dev.resilience4cata.event.FallbackEvent;
import dev.resilience4cata.event.RateLimiterEvent;
import dev.resilience4cata.event.ResilienceEvent;
import dev.resilience4cata.event.RetryEvent;
import dev.resilience4cata.event.TimeoutEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;

/**
 * Turns resilience4cata events into Micrometer counters. Your code, not a library module: copy it.
 * Register it with ResilienceRegistry.Builder.listen(ResilienceEvent.class, ...) before build().
 */
public final class ResilienceEventCounters implements EventListener<ResilienceEvent> {

    private final MeterRegistry meters;

    public ResilienceEventCounters(MeterRegistry meters) {
        this.meters = meters;
    }

    @Override
    public void onEvent(ResilienceEvent event) {
        switch (event) {
            case CircuitBreakerEvent.StateTransition t ->
                    count("r4c.circuitbreaker.transitions", t, "from", lower(t.from()), "to", lower(t.to()));
            case CircuitBreakerEvent.CallNotPermitted r -> count("r4c.calls.rejected", r, "kind", "circuitbreaker");
            case RateLimiterEvent.Rejected r -> count("r4c.calls.rejected", r, "kind", "ratelimiter");
            case BulkheadEvent.Rejected r -> count("r4c.calls.rejected", r, "kind", "bulkhead");
            case RetryEvent.Retrying r -> count("r4c.retry.retries", r);
            case RetryEvent.GaveUp g -> count("r4c.retry.gave.up", g, "reason", lower(g.reason()));
            case TimeoutEvent.TimedOut t -> count("r4c.timeout.timed.out", t);
            case FallbackEvent.Applied f -> count("r4c.fallback.applied", f);
        }
    }

    private void count(String name, ResilienceEvent event, String... tags) {
        Counter.builder(name).tag("instance", event.instance()).tags(tags).register(meters).increment();
    }

    private static String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
// end::observability-counters[]
