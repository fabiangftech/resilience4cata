package dev.resilience4cata;

/**
 * Thrown when an adaptive limit has no free slot for a call: as many calls as its current limit are
 * already running. The call is rejected at once, without waiting. Each adaptive limit reuses one
 * preallocated instance without a stack trace, so a rejection costs almost nothing; the current limit is
 * in {@link AdaptiveLimitHandle#limit()} and in the {@code Rejected} event.
 */
public final class LimitExceededException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String instanceId;

    /**
     * Creates the exception for one adaptive limit.
     *
     * @param instanceId id of the adaptive limit
     */
    public LimitExceededException(String instanceId) {
        super("Adaptive limit '" + instanceId + "' is full", null, false, false);
        this.instanceId = instanceId;
    }

    /** Returns the id of the adaptive limit that rejected the call. */
    public String instanceId() {
        return instanceId;
    }
}
