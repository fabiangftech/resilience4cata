package dev.resilience4cata.event;

import java.time.Duration;

/** Events of an adaptive limit. */
public sealed interface AdaptiveLimitEvent extends ResilienceEvent {

    /**
     * A call was rejected: as many calls as the current limit were already running.
     *
     * @param id the adaptive limit's id
     * @param nanoTime when the call was rejected
     * @param limit the limit at that moment
     */
    record Rejected(String id, long nanoTime, int limit) implements AdaptiveLimitEvent {
    }

    /**
     * A window ended and the limit moved.
     *
     * @param id the adaptive limit's id
     * @param nanoTime when the window ended
     * @param from the limit before
     * @param to the limit from now on
     * @param windowLatency average latency of the window's calls
     */
    record LimitChanged(String id, long nanoTime, int from, int to, Duration windowLatency)
            implements AdaptiveLimitEvent {
    }
}
