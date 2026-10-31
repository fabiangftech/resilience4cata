package dev.resilience4cata.event;

/** Events of a rate limiter. */
public sealed interface RateLimiterEvent extends ResilienceEvent {

    /**
     * A call was rejected: its permit was further away than {@code maxWait}, or the thread was
     * interrupted while waiting for it.
     *
     * @param id the rate limiter's id
     * @param nanoTime when the call was rejected
     */
    record Rejected(String id, long nanoTime) implements RateLimiterEvent {
    }
}
