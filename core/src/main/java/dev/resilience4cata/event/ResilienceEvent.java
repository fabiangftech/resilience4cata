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
 *
 * <p><b>Compatibility.</b> The hierarchy is sealed so that pattern matching works, and it grows in minor
 * versions: a new policy adds its own event interface, and a policy can add event records. Keep a
 * {@code default} branch in a {@code switch} over {@code ResilienceEvent} or over one policy's interface,
 * or register listeners for the record types you need ({@code listen(RetryEvent.GaveUp.class, ...)}), which a
 * new event type never affects. Without a {@code default}, recompiling against a newer version reports the
 * new types; code compiled against an older one that receives a type it does not know throws
 * {@link MatchException} in the listener, which goes to the thread's uncaught exception handler and never
 * reaches the call.
 */
public sealed interface ResilienceEvent
        permits CircuitBreakerEvent, RetryEvent, HedgeEvent, TimeoutEvent, BulkheadEvent, RateLimiterEvent,
        AdaptiveLimitEvent, FallbackEvent {

    /** Id of the policy instance: its {@code name}, or the derived {@code <interface>#<method>(<parameter types>)}. */
    String instance();

    /**
     * When the event happened, in {@link System#nanoTime()} units (or the registry's ticker in tests).
     * Only meaningful as a difference with other such values.
     */
    long nanoTime();
}
