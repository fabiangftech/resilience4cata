package dev.resilience4cata;

/** State of a circuit breaker. */
public enum CircuitBreakerState {
    /** Calls go through, and their outcomes are recorded in the sliding window. */
    CLOSED,
    /** Calls are rejected until the wait in the open state is over. */
    OPEN,
    /** A limited number of probe calls go through to decide whether to close or open again. */
    HALF_OPEN,
    /** Set by {@link CircuitBreakerHandle#forceOpen()}: every call is rejected until {@link CircuitBreakerHandle#reset()}. */
    FORCED_OPEN,
    /**
     * Set by {@link CircuitBreakerHandle#disable()}, or by {@code enabled = false} in the configuration:
     * every call goes through and nothing is recorded.
     */
    DISABLED
}
