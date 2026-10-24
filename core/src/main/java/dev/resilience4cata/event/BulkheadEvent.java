package dev.resilience4cata.event;

/** Events of a bulkhead. */
public sealed interface BulkheadEvent extends ResilienceEvent {

    /**
     * A call was rejected: the bulkhead was full and stayed full for {@code maxWait}, or the waiting
     * thread was interrupted.
     *
     * @param instance the bulkhead's id
     * @param nanoTime when the call was rejected
     * @param maxConcurrent the configured limit
     */
    record Rejected(String instance, long nanoTime, int maxConcurrent) implements BulkheadEvent {
    }
}
