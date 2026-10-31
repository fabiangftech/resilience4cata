package dev.resilience4cata.micrometer;

import static dev.resilience4cata.micrometer.MicrometerMetrics.lower;

import dev.resilience4cata.CircuitBreakerState;
import dev.resilience4cata.event.AdaptiveLimitEvent;
import dev.resilience4cata.event.BulkheadEvent;
import dev.resilience4cata.event.CircuitBreakerEvent;
import dev.resilience4cata.event.EventListener;
import dev.resilience4cata.event.FallbackEvent;
import dev.resilience4cata.event.HedgeEvent;
import dev.resilience4cata.event.RateLimiterEvent;
import dev.resilience4cata.event.ResilienceEvent;
import dev.resilience4cata.event.RetryEvent;
import dev.resilience4cata.event.TimeoutEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * The listener behind {@link MicrometerMetrics#register}: one counter per (name, instance, tag values),
 * registered with Micrometer at its first event and then cached. A cached event costs one map lookup
 * by instance id, one array read and the increment: no allocation besides the event itself.
 */
final class EventCounters implements EventListener<ResilienceEvent> {

    private static final RetryEvent.Reason[] REASONS = RetryEvent.Reason.values();
    private static final CircuitBreakerState[] STATES = CircuitBreakerState.values();
    private static final HedgeEvent.Reason[] HEDGE_REASONS = HedgeEvent.Reason.values();
    private static final HedgeEvent.SkipReason[] SKIP_REASONS = HedgeEvent.SkipReason.values();

    // Slots of Instance.counters. Reasons and states are indexed by ordinal, so new constants get slots.
    private static final int RETRIES = 0;
    private static final int TIMED_OUT = 1;
    private static final int FALLBACK = 2;
    private static final int REJECTED_CIRCUIT_BREAKER = 3;
    private static final int REJECTED_RATE_LIMITER = 4;
    private static final int REJECTED_BULKHEAD = 5;
    private static final int GAVE_UP = 6;
    private static final int TRANSITIONS = GAVE_UP + REASONS.length;
    private static final int HEDGE_WON = TRANSITIONS + STATES.length * STATES.length;
    private static final int HEDGE_FAILED = HEDGE_WON + 1;
    private static final int HEDGED = HEDGE_FAILED + 1;
    private static final int HEDGE_SKIPPED = HEDGED + HEDGE_REASONS.length;
    private static final int REJECTED_ADAPTIVE_LIMIT = HEDGE_SKIPPED + SKIP_REASONS.length;
    private static final int LIMIT_CHANGES = REJECTED_ADAPTIVE_LIMIT + 1;
    private static final int SLOTS = LIMIT_CHANGES + 1;

    private static final String CALLS_REJECTED = "r4c.calls.rejected";

    private final MeterRegistry meters;
    private final ConcurrentHashMap<String, Instance> instances = new ConcurrentHashMap<>();
    private volatile boolean closed;

    EventCounters(MeterRegistry meters) {
        this.meters = meters;
    }

    @Override
    public void onEvent(ResilienceEvent event) {
        if (closed) {
            return;
        }
        switch (event) {
            case CircuitBreakerEvent.StateTransition t -> transition(t);
            case CircuitBreakerEvent.CallNotPermitted r ->
                    count(r, REJECTED_CIRCUIT_BREAKER, CALLS_REJECTED, "kind", "circuitbreaker");
            case RateLimiterEvent.Rejected r -> count(r, REJECTED_RATE_LIMITER, CALLS_REJECTED, "kind", "ratelimiter");
            case BulkheadEvent.Rejected r -> count(r, REJECTED_BULKHEAD, CALLS_REJECTED, "kind", "bulkhead");
            case AdaptiveLimitEvent.Rejected r ->
                    count(r, REJECTED_ADAPTIVE_LIMIT, CALLS_REJECTED, "kind", "adaptivelimit");
            case AdaptiveLimitEvent.LimitChanged c -> count(c, LIMIT_CHANGES, "r4c.adaptivelimit.limit.changes");
            case RetryEvent.Retrying r -> count(r, RETRIES, "r4c.retry.retries");
            case RetryEvent.GaveUp g -> gaveUp(g);
            case TimeoutEvent.TimedOut t -> count(t, TIMED_OUT, "r4c.timeout.timed.out");
            case FallbackEvent.Applied f -> count(f, FALLBACK, "r4c.fallback.applied");
            case HedgeEvent.Hedged h -> hedged(h);
            case HedgeEvent.Won w -> count(w, HEDGE_WON, "r4c.hedge.won");
            case HedgeEvent.Skipped k -> skipped(k);
            case HedgeEvent.Failed f -> count(f, HEDGE_FAILED, "r4c.hedge.failed");
        }
    }

    private void transition(CircuitBreakerEvent.StateTransition t) {
        int slot = TRANSITIONS + t.from().ordinal() * STATES.length + t.to().ordinal();
        Counter counter = instance(t.id()).counters.get(slot);
        if (counter == null) {
            counter = create(t, slot, "r4c.circuitbreaker.transitions", "from", lower(t.from()), "to", lower(t.to()));
        }
        counter.increment();
    }

    private void gaveUp(RetryEvent.GaveUp g) {
        int slot = GAVE_UP + g.reason().ordinal();
        Counter counter = instance(g.id()).counters.get(slot);
        if (counter == null) { // the lowercase tag value is computed only here, not per event
            counter = create(g, slot, "r4c.retry.gave.up", "reason", lower(g.reason()));
        }
        counter.increment();
    }

    private void hedged(HedgeEvent.Hedged h) {
        int slot = HEDGED + h.reason().ordinal();
        Counter counter = instance(h.id()).counters.get(slot);
        if (counter == null) {
            counter = create(h, slot, "r4c.hedge.hedged", "reason", lower(h.reason()));
        }
        counter.increment();
    }

    private void skipped(HedgeEvent.Skipped k) {
        int slot = HEDGE_SKIPPED + k.reason().ordinal();
        Counter counter = instance(k.id()).counters.get(slot);
        if (counter == null) {
            counter = create(k, slot, "r4c.hedge.skipped", "reason", lower(k.reason()));
        }
        counter.increment();
    }

    private void count(ResilienceEvent event, int slot, String name) {
        Counter counter = instance(event.id()).counters.get(slot);
        if (counter == null) {
            counter = create(event, slot, name);
        }
        counter.increment();
    }

    private void count(ResilienceEvent event, int slot, String name, String key, String value) {
        Counter counter = instance(event.id()).counters.get(slot);
        if (counter == null) {
            counter = create(event, slot, name, key, value);
        }
        counter.increment();
    }

    private Instance instance(String id) {
        Instance instance = instances.get(id);
        return instance != null ? instance : instances.computeIfAbsent(id, k -> new Instance());
    }

    /** Cold: the first event of a counter. Two racing threads get the same counter from Micrometer. */
    private Counter create(ResilienceEvent event, int slot, String name, String... tags) {
        Counter counter = Counter.builder(name).description(DESCRIPTIONS.get(name))
                .tag("name", event.id()).tags(tags).register(meters);
        instance(event.id()).counters.set(slot, counter);
        if (closed) { // close() ran meanwhile and may have missed this counter
            meters.remove(counter);
        }
        return counter;
    }

    /** Stops counting and removes every counter registered so far. */
    void close() {
        closed = true;
        for (Instance instance : instances.values()) {
            for (int i = 0; i < SLOTS; i++) {
                Counter counter = instance.counters.get(i);
                if (counter != null) {
                    meters.remove(counter);
                }
            }
        }
        instances.clear();
    }

    Map<String, Object> cacheForTest() {
        return Map.copyOf(instances);
    }

    private static final Map<String, String> DESCRIPTIONS = Map.ofEntries(
            Map.entry(CALLS_REJECTED, "Calls rejected by a circuit breaker, rate limiter, adaptive limit or bulkhead"),
            Map.entry("r4c.circuitbreaker.transitions", "Circuit breaker state changes"),
            Map.entry("r4c.retry.retries", "Attempts that failed and will be retried"),
            Map.entry("r4c.retry.gave.up", "Retries that stopped without a success, by reason"),
            Map.entry("r4c.hedge.hedged", "Hedged attempts started, by reason"),
            Map.entry("r4c.hedge.won", "Calls won by a hedged attempt rather than the first one"),
            Map.entry("r4c.hedge.skipped", "Hedged attempts not started, by reason"),
            Map.entry("r4c.hedge.failed", "Calls through a hedge that failed"),
            Map.entry("r4c.adaptivelimit.limit.changes", "Windows after which an adaptive limit moved its limit"),
            Map.entry("r4c.timeout.timed.out", "Calls that timed out"),
            Map.entry("r4c.fallback.applied", "Failures answered by a fallback"));

    /** The counters of one instance id, by slot. */
    private static final class Instance {
        final AtomicReferenceArray<Counter> counters = new AtomicReferenceArray<>(SLOTS);
    }
}
