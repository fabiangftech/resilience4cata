package dev.resilience4cata.event;

import dev.resilience4cata.CircuitBreakerState;

/** Events of a circuit breaker. */
public sealed interface CircuitBreakerEvent extends ResilienceEvent {

    /**
     * The breaker changed state. Published exactly once per transition, by the thread whose
     * compare-and-set made it. {@code from} and {@code to} can be equal: {@code reset()} on a closed
     * breaker starts a new, empty window.
     *
     * @param instance the breaker's id
     * @param nanoTime when it happened
     * @param from the state before
     * @param to the state after
     */
    record StateTransition(String instance, long nanoTime, CircuitBreakerState from, CircuitBreakerState to)
            implements CircuitBreakerEvent {
    }

    /**
     * A call was rejected because the breaker is open, forced open, or out of half-open probes.
     *
     * @param instance the breaker's id
     * @param nanoTime when it happened
     * @param state the state that rejected the call
     */
    record CallNotPermitted(String instance, long nanoTime, CircuitBreakerState state) implements CircuitBreakerEvent {
    }
}
