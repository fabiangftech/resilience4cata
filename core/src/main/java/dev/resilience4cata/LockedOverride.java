package dev.resilience4cata;

import java.util.Objects;

/**
 * A policy instance whose annotation is locked ({@code locked = true}) and whose per-instance overrides
 * ({@code instances.<id>.<policy>.*} or {@code ResilienceRegistry.Builder.retryInstance(...)} and friends)
 * tried to change some of the values the annotation sets. Those overrides were ignored; the others applied.
 * Listed by {@link ResilienceRegistry#lockedOverrides()}.
 *
 * @param policy the policy, as in configuration keys: {@code retry}, {@code hedge}, {@code circuitBreaker},
 *     {@code rateLimiter}, {@code adaptiveLimit}, {@code timeout} or {@code bulkhead}
 * @param instance the instance id
 * @param config the configuration the instance uses, for example a {@link RetryConfig}
 */
public record LockedOverride(String policy, String instance, Record config) {

    /** Checks that no component is null. */
    public LockedOverride {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(instance, "instance");
        Objects.requireNonNull(config, "config");
    }
}
