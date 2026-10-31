package dev.resilience4cata;

/**
 * Inspects and controls one circuit breaker, for operations and tests. Obtained from
 * {@link ResilienceRegistry#circuitBreaker(String)} once the annotated interface has been wrapped.
 *
 * <p>Implemented by the library only, not meant to be implemented or mocked: methods may be added in minor versions.
 */
public interface CircuitBreakerHandle {

    /**
     * The breaker's id: its {@code name}, or the derived
     * {@code <type>#<method>(<parameter types>)}: the qualified name of the annotated interface or class (nested types joined by {@code .}), then the method name and its erased parameter types, fully qualified and separated by commas without spaces, for example {@code com.acme.PaymentClient#charge(java.lang.String,int)}.
     */
    String id();

    /** The current state. */
    CircuitBreakerState state();

    /** The configuration the breaker was created from. */
    CircuitBreakerConfig config();

    /**
     * Reads the state and the counts of the current window. May allocate and walk the window; it adds
     * nothing to the calls themselves.
     */
    CircuitBreakerSnapshot snapshot();

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
