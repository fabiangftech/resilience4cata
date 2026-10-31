package dev.resilience4cata;

/**
 * Thrown when a rate limiter has no permit for a call: immediately, or because the next permit is
 * further away than {@code maxWait}. Each rate limiter reuses one preallocated instance without a
 * stack trace, so a rejection costs almost nothing. If the caller is interrupted while waiting, the
 * interrupt flag is set again before this is thrown.
 */
public final class RateLimitExceededException extends CallRejectedException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception for one rate limiter.
     *
     * @param id id of the rate limiter
     */
    public RateLimitExceededException(String id) {
        super("Rate limiter '" + id + "' has no permit available", id);
    }
}
