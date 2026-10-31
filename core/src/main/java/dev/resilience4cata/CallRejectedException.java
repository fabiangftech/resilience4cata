package dev.resilience4cata;

/**
 * A call that a policy rejected before running it: an open circuit breaker
 * ({@link CallNotPermittedException}), a full bulkhead ({@link BulkheadFullException}), an exhausted rate limiter
 * ({@link RateLimitExceededException}) or a full adaptive limit ({@link AdaptiveLimitFullException}). Catch this type
 * to handle every rejection the same way, for example as {@code 503 Service Unavailable}.
 *
 * <p>Each policy instance throws one preallocated instance, so that a rejection costs almost nothing: it has no
 * stack trace, {@link #addSuppressed} does nothing, and the same object is thrown to every rejected caller, so do
 * not compare rejections by identity or keep state in them.
 */
public abstract sealed class CallRejectedException extends RuntimeException
        permits CallNotPermittedException, BulkheadFullException, RateLimitExceededException, AdaptiveLimitFullException {

    private static final long serialVersionUID = 1L;

    private final String id;

    /**
     * Creates a rejection without a stack trace or suppressed exceptions.
     *
     * @param message the message
     * @param id id of the policy instance that rejected the call
     */
    CallRejectedException(String message, String id) {
        super(message, null, false, false);
        this.id = id;
    }

    /**
     * Returns the id of the policy instance that rejected the call.
     *
     * @return the instance id
     */
    public final String id() {
        return id;
    }
}
