package dev.resilience4cata;

/**
 * Thrown when an adaptive limit has no free slot for a call: as many calls as its current limit are
 * already running. The call is rejected at once, without waiting. Each adaptive limit reuses one
 * preallocated instance without a stack trace, so a rejection costs almost nothing; the current limit is
 * in {@link AdaptiveLimitHandle#limit()} and in the {@code Rejected} event.
 */
public final class AdaptiveLimitFullException extends CallRejectedException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception for one adaptive limit.
     *
     * @param id id of the adaptive limit
     */
    public AdaptiveLimitFullException(String id) {
        super("Adaptive limit '" + id + "' is full", id);
    }
}
