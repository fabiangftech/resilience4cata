package dev.resilience4cata;

/**
 * Thrown when a circuit breaker rejects a call because it is open, or because its half-open probes
 * are all in use. Each breaker reuses one preallocated instance without a stack trace, so a
 * rejection costs almost nothing.
 */
public final class CallNotPermittedException extends CallRejectedException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception for one circuit breaker.
     *
     * @param id id of the circuit breaker
     */
    public CallNotPermittedException(String id) {
        super("Circuit breaker '" + id + "' does not permit calls", id);
    }
}
