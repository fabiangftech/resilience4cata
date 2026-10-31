package dev.resilience4cata;

/**
 * Thrown when a bulkhead has no free slot for a call: immediately, or after waiting {@code maxWait}.
 * Each bulkhead reuses one preallocated instance without a stack trace, so a rejection costs almost
 * nothing. If the caller is interrupted while waiting, the interrupt flag is set again before this is thrown.
 */
public final class BulkheadFullException extends CallRejectedException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception for one bulkhead.
     *
     * @param id id of the bulkhead
     */
    public BulkheadFullException(String id) {
        super("Bulkhead '" + id + "' is full", id);
    }
}
