package dev.resilience4cata;

/**
 * The immutable configuration of one policy instance: {@link RetryConfig}, {@link HedgeConfig},
 * {@link CircuitBreakerConfig}, {@link RateLimiterConfig}, {@link AdaptiveLimitConfig}, {@link TimeoutConfig} or
 * {@link BulkheadConfig}. Each is built with its {@code builder()} or {@code toBuilder()}, and has an accessor per
 * attribute; attributes may be added in minor versions.
 */
public sealed interface PolicyConfig permits RetryConfig, HedgeConfig, CircuitBreakerConfig, RateLimiterConfig,
        AdaptiveLimitConfig, TimeoutConfig, BulkheadConfig {

    /**
     * Whether the policy acts at all; a disabled policy lets every call through.
     *
     * @return whether it is enabled
     */
    boolean enabled();
}
