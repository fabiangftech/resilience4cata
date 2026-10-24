package dev.resilience4cata;

/**
 * Thrown when a rate limiter has no permit for a call: immediately, or because the next permit is
 * further away than {@code maxWait}. Each rate limiter reuses one preallocated instance without a
 * stack trace, so a rejection costs almost nothing. If the caller is interrupted while waiting, the
 * interrupt flag is set again before this is thrown.
 */
public final class RateLimitExceededException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String instanceId;

    /**
     * Creates the exception for one rate limiter.
     *
     * @param instanceId id of the rate limiter
     */
    public RateLimitExceededException(String instanceId) {
        super("Rate limiter '" + instanceId + "' has no permit available", null, false, false);
        this.instanceId = instanceId;
    }

    /** Returns the id of the rate limiter that rejected the call. */
    public String instanceId() {
        return instanceId;
    }
}
