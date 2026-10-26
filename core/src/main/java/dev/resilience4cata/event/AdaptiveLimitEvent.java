package dev.resilience4cata.event;

/** Events of an adaptive limit. */
public sealed interface AdaptiveLimitEvent extends ResilienceEvent {

    /**
     * A call was rejected: as many calls as the current limit were already running.
     *
     * @param instance the adaptive limit's id
     * @param nanoTime when the call was rejected
     * @param limit the limit at that moment
     */
    record Rejected(String instance, long nanoTime, int limit) implements AdaptiveLimitEvent {
    }

    /**
     * A window ended and the limit moved.
     *
     * @param instance the adaptive limit's id
     * @param nanoTime when the window ended
     * @param from the limit before
     * @param to the limit from now on
     * @param windowLatencyNanos average latency of the window's calls, in nanoseconds
     */
    record LimitChanged(String instance, long nanoTime, int from, int to, long windowLatencyNanos)
            implements AdaptiveLimitEvent {
    }
}
