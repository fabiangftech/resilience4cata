package dev.resilience4cata;

/** Inspects one rate limiter. Obtained from {@link ResilienceRegistry#rateLimiter(String)}. */
public interface RateLimiterHandle {

    /** The rate limiter's id: its {@code name}, or the derived {@code <interface>#<method>(<parameters>)}. */
    String id();

    /** The configuration the rate limiter was created from. */
    RateLimiterConfig config();

    /**
     * Number of calls that would be let through right now without waiting, between 0 and
     * {@code permits}. A snapshot: concurrent calls change it immediately.
     */
    int availablePermits();
}
