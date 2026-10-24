package dev.resilience4cata;

/**
 * State of one bulkhead at one moment. Obtained from {@link BulkheadHandle#snapshot()} or
 * {@link ResilienceRegistry#snapshot()}.
 *
 * @param instance the bulkhead's id
 * @param concurrentCalls calls running inside the bulkhead, between 0 and {@code maxConcurrent}
 * @param waitingCalls calls waiting for a slot (always 0 when {@code maxWait} is 0)
 * @param maxConcurrent the configured limit
 */
public record BulkheadSnapshot(String instance, int concurrentCalls, int waitingCalls, int maxConcurrent) {

    /** Free slots: {@code maxConcurrent - concurrentCalls}. */
    public int availableSlots() {
        return maxConcurrent - concurrentCalls;
    }
}
