package dev.resilience4cata.internal;

import dev.resilience4cata.AdaptiveLimitConfig;
import dev.resilience4cata.BulkheadConfig;
import dev.resilience4cata.CircuitBreakerConfig;
import dev.resilience4cata.HedgeConfig;
import dev.resilience4cata.RateLimiterConfig;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.RetryConfig;
import dev.resilience4cata.TimeoutConfig;
import dev.resilience4cata.generated.AdaptiveLimitPolicy;
import dev.resilience4cata.generated.BulkheadPolicy;
import dev.resilience4cata.generated.CircuitBreakerPolicy;
import dev.resilience4cata.generated.FallbackPolicy;
import dev.resilience4cata.generated.HedgePolicy;
import dev.resilience4cata.generated.RateLimiterPolicy;
import dev.resilience4cata.generated.RetryPolicy;
import dev.resilience4cata.generated.TimeoutPolicy;
import java.util.List;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * The bridge from {@code dev.resilience4cata.generated} into {@link ResilienceRegistry}'s package-private policy
 * factories: implemented once, by a private class of the registry, which installs it when it is initialized. This
 * package is not exported, so the bridge is not part of the API, and the factories stay out of the registry's
 * public methods.
 */
public abstract class RegistryAccess {

    private static volatile @Nullable RegistryAccess installed;

    /** For the registry's own implementation only. */
    protected RegistryAccess() {
    }

    /**
     * Installs the registry's implementation; called once, from {@link ResilienceRegistry}'s static initializer.
     *
     * @param access the implementation
     * @throws IllegalStateException if called again, or with a class that is not the registry's
     */
    public static void install(RegistryAccess access) {
        if (installed != null || access.getClass().getNestHost() != ResilienceRegistry.class) {
            throw new IllegalStateException("RegistryAccess is installed by ResilienceRegistry only");
        }
        installed = access;
    }

    /**
     * The registry's implementation. Every caller already holds a registry, so the registry class is initialized
     * and the bridge installed.
     *
     * @return the implementation
     */
    public static RegistryAccess get() {
        RegistryAccess access = installed;
        if (access == null) {
            throw new IllegalStateException("ResilienceRegistry is not initialized");
        }
        return access;
    }

    /** See {@link dev.resilience4cata.generated.Policies#retry}. */
    public abstract RetryPolicy retryPolicy(ResilienceRegistry registry, String instanceId, String name, String config,
            @Nullable Consumer<RetryConfig.Builder> overrides, boolean locked);

    /** See {@link dev.resilience4cata.generated.Policies#hedge}. */
    public abstract HedgePolicy hedgePolicy(ResilienceRegistry registry, String instanceId, String name, String config,
            @Nullable Consumer<HedgeConfig.Builder> overrides, boolean locked);

    /** See {@link dev.resilience4cata.generated.Policies#circuitBreaker}. */
    public abstract CircuitBreakerPolicy circuitBreakerPolicy(ResilienceRegistry registry, String instanceId,
            String name, String config, @Nullable Consumer<CircuitBreakerConfig.Builder> overrides, boolean locked);

    /** See {@link dev.resilience4cata.generated.Policies#rateLimiter}. */
    public abstract RateLimiterPolicy rateLimiterPolicy(ResilienceRegistry registry, String instanceId, String name,
            String config, @Nullable Consumer<RateLimiterConfig.Builder> overrides, boolean locked);

    /** See {@link dev.resilience4cata.generated.Policies#adaptiveLimit}. */
    public abstract AdaptiveLimitPolicy adaptiveLimitPolicy(ResilienceRegistry registry, String instanceId,
            String name, String config, @Nullable Consumer<AdaptiveLimitConfig.Builder> overrides, boolean locked);

    /** See {@link dev.resilience4cata.generated.Policies#timeout}. */
    public abstract TimeoutPolicy timeoutPolicy(ResilienceRegistry registry, String instanceId, String name,
            String config, @Nullable Consumer<TimeoutConfig.Builder> overrides, boolean locked);

    /** See {@link dev.resilience4cata.generated.Policies#bulkhead}. */
    public abstract BulkheadPolicy bulkheadPolicy(ResilienceRegistry registry, String instanceId, String name,
            String config, @Nullable Consumer<BulkheadConfig.Builder> overrides, boolean locked);

    /** See {@link dev.resilience4cata.generated.Policies#fallback}. */
    public abstract FallbackPolicy fallbackPolicy(ResilienceRegistry registry, String instanceId,
            List<Class<? extends Throwable>> applyOn, List<Class<? extends Throwable>> skipOn,
            @Nullable List<Class<? extends Throwable>> taken);

    /**
     * The {@code R4C-RT-021} exception.
     *
     * @param component the artifact that is too old
     * @param generated the generated class
     * @param level the level it needs
     * @param supported the level this artifact supports
     * @return the exception, to throw
     */
    public abstract IllegalStateException runtimeTooOld(String component, Class<?> generated, int level, int supported);
}
