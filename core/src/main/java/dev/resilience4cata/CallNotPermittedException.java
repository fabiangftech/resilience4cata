package dev.resilience4cata;

/**
 * Thrown when a circuit breaker rejects a call because it is open, or because its half-open probes
 * are all in use. Each breaker reuses one preallocated instance without a stack trace, so a
 * rejection costs almost nothing.
 */
public final class CallNotPermittedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String instanceId;

    /**
     * Creates the exception for one circuit breaker.
     *
     * @param instanceId id of the circuit breaker
     */
    public CallNotPermittedException(String instanceId) {
        super("Circuit breaker '" + instanceId + "' does not permit calls", null, false, false);
        this.instanceId = instanceId;
    }

    /** Returns the id of the circuit breaker that rejected the call. */
    public String instanceId() {
        return instanceId;
    }
}
