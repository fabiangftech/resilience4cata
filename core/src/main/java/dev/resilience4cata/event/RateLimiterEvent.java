package dev.resilience4cata.event;

/** Events of a rate limiter. */
public sealed interface RateLimiterEvent extends ResilienceEvent {

    /**
     * A call was rejected: its permit was further away than {@code maxWait}, or the thread was
     * interrupted while waiting for it.
     *
     * @param instance the rate limiter's id
     * @param nanoTime when the call was rejected
     */
    record Rejected(String instance, long nanoTime) implements RateLimiterEvent {
    }
}
