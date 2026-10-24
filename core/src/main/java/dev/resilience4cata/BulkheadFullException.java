package dev.resilience4cata;

/**
 * Thrown when a bulkhead has no free slot for a call: immediately, or after waiting {@code maxWait}.
 * Each bulkhead reuses one preallocated instance without a stack trace, so a rejection costs almost
 * nothing. If the caller is interrupted while waiting, the interrupt flag is set again before this is thrown.
 */
public final class BulkheadFullException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String instanceId;

    /**
     * Creates the exception for one bulkhead.
     *
     * @param instanceId id of the bulkhead
     */
    public BulkheadFullException(String instanceId) {
        super("Bulkhead '" + instanceId + "' is full", null, false, false);
        this.instanceId = instanceId;
    }

    /** Returns the id of the bulkhead that rejected the call. */
    public String instanceId() {
        return instanceId;
    }
}
