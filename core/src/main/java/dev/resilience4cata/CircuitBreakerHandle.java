package dev.resilience4cata;

/**
 * Inspects and controls one circuit breaker, for operations and tests. Obtained from
 * {@link ResilienceRegistry#circuitBreaker(String)} once the annotated interface has been wrapped.
 */
public interface CircuitBreakerHandle {

    /** The breaker's id: its {@code name}, or the derived {@code <interface>#<method>(<parameters>)}. */
    String id();

    /** The current state. */
    CircuitBreakerState state();

    /** The configuration the breaker was created from. */
    CircuitBreakerConfig config();

    /** Rejects every call until {@link #reset()}, whatever the outcomes. */
    void forceOpen();

    /** Lets every call through and records nothing until {@link #reset()}. */
    void disable();

    /**
     * Goes back to the initial state with an empty window: closed, or disabled if the configuration
     * has {@code enabled = false}.
     */
    void reset();
}
