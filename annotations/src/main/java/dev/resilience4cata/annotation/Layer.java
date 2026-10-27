package dev.resilience4cata.annotation;

/**
 * A policy layer that {@link PolicyOrder} can move, in the default nesting order, outermost first. The fallback is
 * not one of them: it is always the outermost layer, so that it sees the final outcome.
 */
public enum Layer {

    /** {@link Retry}. */
    RETRY,

    /** {@link Hedge}, which takes the retry's place by default (the two are exclusive on one method). */
    HEDGE,

    /** {@link CircuitBreaker}. */
    CIRCUIT_BREAKER,

    /** {@link RateLimiter}. */
    RATE_LIMITER,

    /** {@link AdaptiveLimit}. */
    ADAPTIVE_LIMIT,

    /** {@link Timeout}. */
    TIMEOUT,

    /** {@link Bulkhead}. */
    BULKHEAD
}
