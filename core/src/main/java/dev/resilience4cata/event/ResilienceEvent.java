package dev.resilience4cata.event;

/**
 * An event published by a resilience policy. Every event is a record nested in one of the policy
 * interfaces, so listeners can use pattern matching:
 *
 * <pre>{@code
 * switch (event) {
 *     case CircuitBreakerEvent.StateTransition t -> alert(t.instance() + ": " + t.from() + " -> " + t.to());
 *     case RetryEvent.Retrying r -> log.debug("retry {} of {}", r.attempt(), r.instance());
 *     default -> { }
 * }
 * }</pre>
 */
public sealed interface ResilienceEvent
        permits CircuitBreakerEvent, RetryEvent, HedgeEvent, TimeoutEvent, BulkheadEvent, RateLimiterEvent, FallbackEvent {

    /** Id of the policy instance: its {@code name}, or the derived {@code <interface>#<method>(<parameter types>)}. */
    String instance();

    /**
     * When the event happened, in {@link System#nanoTime()} units (or the registry's ticker in tests).
     * Only meaningful as a difference with other such values.
     */
    long nanoTime();
}
