package dev.resilience4cata;

/**
 * State of one rate limiter at one moment. Obtained from {@link RateLimiterHandle#snapshot()} or
 * {@link ResilienceRegistry#snapshot()}.
 *
 * @param instance the rate limiter's id
 * @param availablePermits calls that would be let through right now without waiting, between 0 and {@code permits}
 * @param permits the configured permits per period
 */
public record RateLimiterSnapshot(String instance, int availablePermits, int permits) {
}
