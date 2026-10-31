package dev.resilience4cata.generated;

import dev.resilience4cata.AdaptiveLimitConfig;
import dev.resilience4cata.BulkheadConfig;
import dev.resilience4cata.CircuitBreakerConfig;
import dev.resilience4cata.HedgeConfig;
import dev.resilience4cata.RateLimiterConfig;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.RetryConfig;
import dev.resilience4cata.TimeoutConfig;
import dev.resilience4cata.internal.RegistryAccess;
import java.util.List;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * The policies of one call site, as the constructors of generated classes get them from a registry. For generated
 * code only.
 *
 * <p>Every method creates the instance on first use, or returns the shared one: call sites with the same
 * {@code name} share it. The instance's configuration is resolved once: the registry's defaults, then the named
 * {@code config}, then the values set in the annotations ({@code overrides}), then the registry's per-instance
 * overrides, and with {@code locked} the annotations' values again.
 */
public final class Policies {

    private Policies() {
    }

    /**
     * The retry of a call site.
     *
     * @param registry the registry
     * @param instanceId derived id of the call site, used when {@code name} is empty
     * @param name explicit instance name from the annotation, or {@code ""}
     * @param config named configuration to start from, or {@code ""}
     * @param overrides values set in the annotations, or {@code null}
     * @param locked {@code locked = true} on the annotation: its values win over per-instance overrides
     * @return the policy
     * @throws IllegalStateException if two call sites share a name but resolve to different configurations
     */
    public static RetryPolicy retry(ResilienceRegistry registry, String instanceId, String name, String config,
            @Nullable Consumer<RetryConfig.Builder> overrides, boolean locked) {
        return RegistryAccess.get().retryPolicy(registry, instanceId, name, config, overrides, locked);
    }

    /**
     * The hedge of a call site; call sites with the same {@code name} share it, and so its budget.
     *
     * @param registry the registry
     * @param instanceId derived id of the call site, used when {@code name} is empty
     * @param name explicit instance name from the annotation, or {@code ""}
     * @param config named configuration to start from, or {@code ""}
     * @param overrides values set in the annotations, or {@code null}
     * @param locked {@code locked = true} on the annotation: its values win over per-instance overrides
     * @return the policy
     * @throws IllegalStateException if two call sites share a name but resolve to different configurations
     */
    public static HedgePolicy hedge(ResilienceRegistry registry, String instanceId, String name, String config,
            @Nullable Consumer<HedgeConfig.Builder> overrides, boolean locked) {
        return RegistryAccess.get().hedgePolicy(registry, instanceId, name, config, overrides, locked);
    }

    /**
     * The circuit breaker of a call site.
     *
     * @param registry the registry
     * @param instanceId derived id of the call site, used when {@code name} is empty
     * @param name explicit instance name from the annotation, or {@code ""}
     * @param config named configuration to start from, or {@code ""}
     * @param overrides values set in the annotations, or {@code null}
     * @param locked {@code locked = true} on the annotation: its values win over per-instance overrides
     * @return the policy
     * @throws IllegalStateException if two call sites share a name but resolve to different configurations
     */
    public static CircuitBreakerPolicy circuitBreaker(ResilienceRegistry registry, String instanceId, String name,
            String config, @Nullable Consumer<CircuitBreakerConfig.Builder> overrides, boolean locked) {
        return RegistryAccess.get().circuitBreakerPolicy(registry, instanceId, name, config, overrides, locked);
    }

    /**
     * The rate limiter of a call site.
     *
     * @param registry the registry
     * @param instanceId derived id of the call site, used when {@code name} is empty
     * @param name explicit instance name from the annotation, or {@code ""}
     * @param config named configuration to start from, or {@code ""}
     * @param overrides values set in the annotations, or {@code null}
     * @param locked {@code locked = true} on the annotation: its values win over per-instance overrides
     * @return the policy
     * @throws IllegalStateException if two call sites share a name but resolve to different configurations
     */
    public static RateLimiterPolicy rateLimiter(ResilienceRegistry registry, String instanceId, String name,
            String config, @Nullable Consumer<RateLimiterConfig.Builder> overrides, boolean locked) {
        return RegistryAccess.get().rateLimiterPolicy(registry, instanceId, name, config, overrides, locked);
    }

    /**
     * The adaptive limit of a call site.
     *
     * @param registry the registry
     * @param instanceId derived id of the call site, used when {@code name} is empty
     * @param name explicit instance name from the annotation, or {@code ""}
     * @param config named configuration to start from, or {@code ""}
     * @param overrides values set in the annotations, or {@code null}
     * @param locked {@code locked = true} on the annotation: its values win over per-instance overrides
     * @return the policy
     * @throws IllegalStateException if two call sites share a name but resolve to different configurations
     */
    public static AdaptiveLimitPolicy adaptiveLimit(ResilienceRegistry registry, String instanceId, String name,
            String config, @Nullable Consumer<AdaptiveLimitConfig.Builder> overrides, boolean locked) {
        return RegistryAccess.get().adaptiveLimitPolicy(registry, instanceId, name, config, overrides, locked);
    }

    /**
     * The timeout of a call site. Timeouts hold no state shared between calls: every call site gets its own policy.
     *
     * @param registry the registry
     * @param instanceId derived id of the call site, used when {@code name} is empty
     * @param name explicit instance name, or {@code ""}
     * @param config named configuration to start from, or {@code ""}
     * @param overrides values set in the annotations, or {@code null}
     * @param locked {@code locked = true} on the annotation: its values win over per-instance overrides
     * @return the policy
     */
    public static TimeoutPolicy timeout(ResilienceRegistry registry, String instanceId, String name, String config,
            @Nullable Consumer<TimeoutConfig.Builder> overrides, boolean locked) {
        return RegistryAccess.get().timeoutPolicy(registry, instanceId, name, config, overrides, locked);
    }

    /**
     * The bulkhead of a call site.
     *
     * @param registry the registry
     * @param instanceId derived id of the call site, used when {@code name} is empty
     * @param name explicit instance name from the annotation, or {@code ""}
     * @param config named configuration to start from, or {@code ""}
     * @param overrides values set in the annotations, or {@code null}
     * @param locked {@code locked = true} on the annotation: its values win over per-instance overrides
     * @return the policy
     * @throws IllegalStateException if two call sites share a name but resolve to different configurations
     */
    public static BulkheadPolicy bulkhead(ResilienceRegistry registry, String instanceId, String name, String config,
            @Nullable Consumer<BulkheadConfig.Builder> overrides, boolean locked) {
        return RegistryAccess.get().bulkheadPolicy(registry, instanceId, name, config, overrides, locked);
    }

    /**
     * The fallback of a method. Fallbacks hold no state: every method gets its own; the registry adds its listeners.
     *
     * @param registry the registry
     * @param instanceId id of the method, {@code <type>#<method>(<parameter types>)}
     * @param applyOn failures that get the fallback
     * @param skipOn failures that never get it; wins over {@code applyOn}
     * @param taken the exception types of sibling fallback methods that do not take every failure (any other failure
     *     is not handled), or {@code null}
     * @return the policy
     */
    public static FallbackPolicy fallback(ResilienceRegistry registry, String instanceId,
            List<Class<? extends Throwable>> applyOn, List<Class<? extends Throwable>> skipOn,
            @Nullable List<Class<? extends Throwable>> taken) {
        return RegistryAccess.get().fallbackPolicy(registry, instanceId, applyOn, skipOn, taken);
    }
}
