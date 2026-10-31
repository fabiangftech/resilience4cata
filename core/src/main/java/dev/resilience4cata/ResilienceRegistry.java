package dev.resilience4cata;

import dev.resilience4cata.generated.AdaptiveLimitPolicy;
import dev.resilience4cata.generated.BulkheadPolicy;
import dev.resilience4cata.generated.CircuitBreakerPolicy;
import dev.resilience4cata.generated.EventDispatcher;
import dev.resilience4cata.generated.FallbackPolicy;
import dev.resilience4cata.generated.HedgePolicy;
import dev.resilience4cata.generated.PolicyEnvironment;
import dev.resilience4cata.generated.RateLimiterPolicy;
import dev.resilience4cata.generated.RetryPolicy;
import dev.resilience4cata.generated.TimeoutPolicy;
import dev.resilience4cata.internal.RegistryAccess;
import dev.resilience4cata.event.EventListener;
import dev.resilience4cata.event.ResilienceEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.random.RandomGenerator;
import org.jspecify.annotations.Nullable;

/**
 * Holds the named configurations and the policy instances created from them. Configuration is
 * immutable once the registry is built.
 *
 * <pre>{@code
 * ResilienceRegistry registry = ResilienceRegistry.builder()
 *         .retryDefaults(r -> r.maxAttempts(3))
 *         .retry("external", r -> r.maxAttempts(5).delay(Duration.ofMillis(500)))
 *         .circuitBreaker("external", cb -> cb.failureRateThreshold(25))
 *         .build();
 * }</pre>
 */
// java:S6539: the registry is the one place that resolves every policy's configuration and shares its
// instances, so it knows every policy type by design; its builder and records are already split out.
@SuppressWarnings("java:S6539")
public final class ResilienceRegistry {

    static {
        RegistryAccess.install(new Access());
    }

    private static final String DEFAULT = "default";
    private static final String RETRY_NAME = "retry";
    private static final String HEDGE_NAME = "hedge";
    private static final String CIRCUIT_BREAKER_NAME = "circuitBreaker";
    private static final String RATE_LIMITER_NAME = "rateLimiter";
    private static final String TIMEOUT_NAME = "timeout";
    private static final String BULKHEAD_NAME = "bulkhead";
    private static final String ADAPTIVE_LIMIT_NAME = "adaptiveLimit";
    private static final String DEFAULTS = "defaults";
    private static final String CONFIG_QUOTE = " config '";
    private static final String CONFIG = "config";
    private static final String OVERLAY = "overlay";
    private static final String OVERRIDES = "overrides";
    private static final String OVERLAY_METHOD = "Overlay";
    private static final String ANNOTATION_OR_STEP = "the values set in its annotation or pipeline step";
    private static final System.Logger LOGGER = System.getLogger(ResilienceRegistry.class.getName());

    private static final PolicyKind<RetryConfig, RetryConfig.Builder> RETRY_KIND =
            new PolicyKind<>(RETRY_NAME, RetryConfig::toBuilder, RetryConfig.Builder::build);
    private static final PolicyKind<HedgeConfig, HedgeConfig.Builder> HEDGE_KIND =
            new PolicyKind<>(HEDGE_NAME, HedgeConfig::toBuilder, HedgeConfig.Builder::build);
    private static final PolicyKind<CircuitBreakerConfig, CircuitBreakerConfig.Builder> CIRCUIT_BREAKER_KIND =
            new PolicyKind<>(CIRCUIT_BREAKER_NAME, CircuitBreakerConfig::toBuilder, CircuitBreakerConfig.Builder::build);
    private static final PolicyKind<RateLimiterConfig, RateLimiterConfig.Builder> RATE_LIMITER_KIND =
            new PolicyKind<>(RATE_LIMITER_NAME, RateLimiterConfig::toBuilder, RateLimiterConfig.Builder::build);
    private static final PolicyKind<TimeoutConfig, TimeoutConfig.Builder> TIMEOUT_KIND =
            new PolicyKind<>(TIMEOUT_NAME, TimeoutConfig::toBuilder, TimeoutConfig.Builder::build);
    private static final PolicyKind<BulkheadConfig, BulkheadConfig.Builder> BULKHEAD_KIND =
            new PolicyKind<>(BULKHEAD_NAME, BulkheadConfig::toBuilder, BulkheadConfig.Builder::build);
    private static final PolicyKind<AdaptiveLimitConfig, AdaptiveLimitConfig.Builder> ADAPTIVE_LIMIT_KIND =
            new PolicyKind<>(ADAPTIVE_LIMIT_NAME, AdaptiveLimitConfig::toBuilder, AdaptiveLimitConfig.Builder::build);

    private final RetryConfig retryDefaults;
    private final Map<String, RetryConfig> retryConfigs;
    private final ConcurrentHashMap<String, RetryPolicy> retries = new ConcurrentHashMap<>();
    private final HedgeConfig hedgeDefaults;
    private final Map<String, HedgeConfig> hedgeConfigs;
    private final ConcurrentHashMap<String, HedgePolicy> hedges = new ConcurrentHashMap<>();
    private final CircuitBreakerConfig circuitBreakerDefaults;
    private final Map<String, CircuitBreakerConfig> circuitBreakerConfigs;
    private final ConcurrentHashMap<String, CircuitBreakerPolicy> circuitBreakers = new ConcurrentHashMap<>();
    private final RateLimiterConfig rateLimiterDefaults;
    private final Map<String, RateLimiterConfig> rateLimiterConfigs;
    private final ConcurrentHashMap<String, RateLimiterPolicy> rateLimiters = new ConcurrentHashMap<>();
    private final TimeoutConfig timeoutDefaults;
    private final Map<String, TimeoutConfig> timeoutConfigs;
    private final ConcurrentHashMap<String, TimeoutPolicy> timeouts = new ConcurrentHashMap<>();
    private final BulkheadConfig bulkheadDefaults;
    private final Map<String, BulkheadConfig> bulkheadConfigs;
    private final ConcurrentHashMap<String, BulkheadPolicy> bulkheads = new ConcurrentHashMap<>();
    private final AdaptiveLimitConfig adaptiveLimitDefaults;
    private final Map<String, AdaptiveLimitConfig> adaptiveLimitConfigs;
    private final ConcurrentHashMap<String, AdaptiveLimitPolicy> adaptiveLimits = new ConcurrentHashMap<>();
    private final PolicyEnvironment environment;
    private final Instances instances;
    /** {@link #onInstanceCreated} actions; read only when an instance is created, never on a call. */
    private final CopyOnWriteArrayList<Runnable> creationListeners = new CopyOnWriteArrayList<>();
    /** {@link #lockedOverrides()}, by policy and instance id; written only when an instance is created. */
    private final ConcurrentHashMap<String, LockedOverride> lockedOverrides = new ConcurrentHashMap<>();

    // java:S107: one resolved configuration set per policy kind, built once by the builder.
    @SuppressWarnings("java:S107")
    private ResilienceRegistry(Configs<RetryConfig> retry, Configs<HedgeConfig> hedge,
            Configs<CircuitBreakerConfig> circuitBreaker, Configs<RateLimiterConfig> rateLimiter,
            Configs<TimeoutConfig> timeout, Configs<BulkheadConfig> bulkhead, Configs<AdaptiveLimitConfig> adaptiveLimit,
            PolicyEnvironment environment, Instances instances) {
        this.retryDefaults = retry.defaults();
        this.retryConfigs = Map.copyOf(retry.named());
        this.hedgeDefaults = hedge.defaults();
        this.hedgeConfigs = Map.copyOf(hedge.named());
        this.circuitBreakerDefaults = circuitBreaker.defaults();
        this.circuitBreakerConfigs = Map.copyOf(circuitBreaker.named());
        this.rateLimiterDefaults = rateLimiter.defaults();
        this.rateLimiterConfigs = Map.copyOf(rateLimiter.named());
        this.timeoutDefaults = timeout.defaults();
        this.timeoutConfigs = Map.copyOf(timeout.named());
        this.bulkheadDefaults = bulkhead.defaults();
        this.bulkheadConfigs = Map.copyOf(bulkhead.named());
        this.adaptiveLimitDefaults = adaptiveLimit.defaults();
        this.adaptiveLimitConfigs = Map.copyOf(adaptiveLimit.named());
        this.environment = environment;
        this.instances = instances;
    }

    /** One policy's resolved defaults and named configurations, as built by {@link Builder#build()}. */
    private record Configs<C>(C defaults, Map<String, C> named) {
    }

    /** How to rebuild one policy's configuration, and the name used for it in messages. */
    private record PolicyKind<C, B>(String name, Function<C, B> toBuilder, Function<B, C> build) {
    }

    /** Per-instance overrides ({@link Builder#retryInstance}, ...), by instance id. Immutable. */
    private record Instances(Map<String, Consumer<RetryConfig.Builder>> retry,
            Map<String, Consumer<HedgeConfig.Builder>> hedge,
            Map<String, Consumer<CircuitBreakerConfig.Builder>> circuitBreaker,
            Map<String, Consumer<RateLimiterConfig.Builder>> rateLimiter,
            Map<String, Consumer<TimeoutConfig.Builder>> timeout,
            Map<String, Consumer<BulkheadConfig.Builder>> bulkhead,
            Map<String, Consumer<AdaptiveLimitConfig.Builder>> adaptiveLimit) {
        Instances {
            retry = Map.copyOf(retry);
            hedge = Map.copyOf(hedge);
            circuitBreaker = Map.copyOf(circuitBreaker);
            rateLimiter = Map.copyOf(rateLimiter);
            timeout = Map.copyOf(timeout);
            bulkhead = Map.copyOf(bulkhead);
            adaptiveLimit = Map.copyOf(adaptiveLimit);
        }
    }

    /**
     * Resolves one instance's configuration: the named config ({@code base}), then the annotation's or
     * pipeline step's values ({@code overrides}), then the instance's own overrides ({@code instance}).
     * When {@code locked}, the annotation's values are applied once more at the end, so they win over
     * the instance's overrides; an override that this changes is recorded and logged once per instance.
     * Runs once per call site, when the instance is created.
     */
    private <C extends PolicyConfig, B> C resolve(C base, @Nullable Consumer<B> overrides, @Nullable Consumer<B> instance,
            boolean locked, PolicyKind<C, B> policy, String key, String config) {
        if (overrides == null && instance == null) {
            return base;
        }
        B builder = policy.toBuilder().apply(base);
        if (overrides != null) {
            overrides.accept(builder);
        }
        if (instance != null) {
            instance.accept(builder);
        }
        boolean relock = locked && overrides != null && instance != null;
        C requested = null;
        if (relock) {
            requested = buildOrNull(policy, builder);
            overrides.accept(builder);
        }
        C resolved;
        try {
            resolved = policy.build().apply(builder);
        } catch (IllegalArgumentException e) {
            String kind = policy.name();
            String named = config.isEmpty() || config.equals(DEFAULT) ? "the " + kind + " defaults"
                    : "named config '" + config + "'";
            String sources = instance == null ? ANNOTATION_OR_STEP : instanceSources(kind, key, overrides != null);
            if (relock) {
                sources += ", then " + ANNOTATION_OR_STEP + " again (locked)";
            }
            throw new IllegalArgumentException(ErrorCode.INVALID_INSTANCE_CONFIG.message("Invalid " + kind
                    + " config for instance '" + key + "': " + ErrorCode.detail(e) + ". It starts from " + named
                    + " and applies " + sources + "; fix the value there so that the combination is valid."), e);
        }
        if (relock && !resolved.equals(requested)) {
            lockedOverride(policy.name(), key, resolved, requested);
        }
        return resolved;
    }

    /** The configuration a builder holds now, or {@code null} if it is invalid. */
    private static <C, B> @Nullable C buildOrNull(PolicyKind<C, B> policy, B builder) {
        try {
            return policy.build().apply(builder);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Records that a locked instance ignored some of its per-instance overrides; logs it the first time. */
    private void lockedOverride(String policy, String key, PolicyConfig kept, @Nullable PolicyConfig requested) {
        LockedOverride override = new LockedOverride(policy, key, kept);
        if (lockedOverrides.putIfAbsent(policy + '\u0000' + key, override) == null) {
            LOGGER.log(System.Logger.Level.WARNING, () -> "resilience4cata: the " + policy + " instance '" + key
                    + "' is locked (locked = true on its annotation), so the values its annotation sets win over its"
                    + " per-instance overrides (" + policy + "Instance(...) or instances.<id>." + policy + ".*),"
                    + " and some of those overrides were ignored. Kept: " + kept + (requested == null
                            ? "; the overrides alone would give an invalid configuration."
                            : "; the overrides would have given: " + requested + ".")
                    + " Remove the overrides of the locked values, or remove locked = true.");
        }
    }

    /**
     * Returns the per-instance overrides that a locked annotation ({@code locked = true}) made ineffective, at
     * least in part, among the instances created so far: one entry per instance, sorted by policy and id.
     * {@code ResilienceConfig.verifyInstances} reports them key by key. Off the call path.
     *
     * @return the locked instances whose per-instance overrides were partly ignored
     */
    public List<LockedOverride> lockedOverrides() {
        return lockedOverrides.values().stream()
                .sorted(Comparator.comparing(LockedOverride::policy).thenComparing(LockedOverride::instance))
                .toList();
    }

    /** Where an instance's values come from, for a message, when it has per-instance overrides. */
    private static String instanceSources(String kind, String key, boolean hasOverrides) {
        String prefix = hasOverrides ? ANNOTATION_OR_STEP + ", then " : "";
        String externalId = key.matches("[A-Za-z0-9_-]+") ? key : '"' + key + '"';
        return prefix + "its per-instance overrides (" + kind + "Instance(\"" + key + "\", ...), or instances."
                + externalId + "." + kind + ".* in external configuration)";
    }

    /** Two call sites share an instance id but resolve to different configurations. */
    private static IllegalStateException conflict(String what, String key, Object existing, Object resolved) {
        return new IllegalStateException(ErrorCode.CONFLICTING_INSTANCE.message(what + " '" + key
                + "' is used with two different configurations (a shared name, or the same pipeline id"
                + " built twice):\n  " + existing + "\n  " + resolved
                + "\nGive the call sites different names, or make their configuration identical."));
    }

    /** Returns a new builder. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns a named retry configuration.
     *
     * @param config the name; {@code ""} or {@code "default"} means the defaults
     * @return the resolved configuration
     * @throws IllegalArgumentException if no configuration has that name
     */
    public RetryConfig retryConfig(String config) {
        return lookup(config, retryDefaults, retryConfigs, RETRY_NAME);
    }

    /**
     * Returns a named hedge configuration.
     *
     * @param config the name; {@code ""} or {@code "default"} means the defaults
     * @return the resolved configuration
     * @throws IllegalArgumentException if no configuration has that name
     */
    public HedgeConfig hedgeConfig(String config) {
        return lookup(config, hedgeDefaults, hedgeConfigs, HEDGE_NAME);
    }

    /**
     * Returns a named circuit breaker configuration.
     *
     * @param config the name; {@code ""} or {@code "default"} means the defaults
     * @return the resolved configuration
     * @throws IllegalArgumentException if no configuration has that name
     */
    public CircuitBreakerConfig circuitBreakerConfig(String config) {
        return lookup(config, circuitBreakerDefaults, circuitBreakerConfigs, CIRCUIT_BREAKER_NAME);
    }

    /**
     * Returns a named rate limiter configuration.
     *
     * @param config the name; {@code ""} or {@code "default"} means the defaults
     * @return the resolved configuration
     * @throws IllegalArgumentException if no configuration has that name
     */
    public RateLimiterConfig rateLimiterConfig(String config) {
        return lookup(config, rateLimiterDefaults, rateLimiterConfigs, RATE_LIMITER_NAME);
    }

    /**
     * Returns a named timeout configuration.
     *
     * @param config the name; {@code ""} or {@code "default"} means the defaults
     * @return the resolved configuration
     * @throws IllegalArgumentException if no configuration has that name
     */
    public TimeoutConfig timeoutConfig(String config) {
        return lookup(config, timeoutDefaults, timeoutConfigs, TIMEOUT_NAME);
    }

    /**
     * Returns a named bulkhead configuration.
     *
     * @param config the name; {@code ""} or {@code "default"} means the defaults
     * @return the resolved configuration
     * @throws IllegalArgumentException if no configuration has that name
     */
    public BulkheadConfig bulkheadConfig(String config) {
        return lookup(config, bulkheadDefaults, bulkheadConfigs, BULKHEAD_NAME);
    }

    /**
     * Returns a named adaptive limit configuration.
     *
     * @param config the name; {@code ""} or {@code "default"} means the defaults
     * @return the resolved configuration
     * @throws IllegalArgumentException if no configuration has that name
     */
    public AdaptiveLimitConfig adaptiveLimitConfig(String config) {
        return lookup(config, adaptiveLimitDefaults, adaptiveLimitConfigs, ADAPTIVE_LIMIT_NAME);
    }

    private static <C> C lookup(String config, C defaults, Map<String, C> named, String kind) {
        if (config.isEmpty() || config.equals(DEFAULT)) {
            return defaults;
        }
        C found = named.get(config);
        if (found == null) {
            throw new IllegalArgumentException(ErrorCode.UNKNOWN_CONFIG.message("Unknown " + kind + CONFIG_QUOTE
                    + config + "' (config = \"" + config + "\" in an annotation or pipeline step)."
                    + Suggestions.didYouMean(config, named.keySet()) + " Known " + kind + " configs: "
                    + new TreeSet<>(named.keySet()) + ". Register it with ResilienceRegistry.builder()." + kind
                    + "(\"" + config + "\", c -> ...), or with configs." + config + "." + kind
                    + ".* in external configuration."));
        }
        return found;
    }


    /**
     * Returns the retry policy for one call site, creating it on first use. Used by {@link Pipeline} and,
     * through {@link dev.resilience4cata.generated.Policies}, by generated code.
     *
     * @param instanceId derived id of the call site, used when {@code name} is empty
     * @param name explicit instance name from the annotation, or {@code ""}
     * @param config named configuration to start from, or {@code ""}
     * @param overrides values set in the annotations, or {@code null}
     * @param locked {@code locked = true} on the annotation: the values set in {@code overrides} win over
     *     the instance's per-instance overrides
     * @return the policy
     * @throws IllegalStateException if two call sites share a name but resolve to different configurations
     */
    RetryPolicy retryPolicy(String instanceId, String name, String config,
            @Nullable Consumer<RetryConfig.Builder> overrides, boolean locked) {
        String key = name.isEmpty() ? instanceId : name;
        RetryConfig resolved = resolve(retryConfig(config), overrides, instances.retry().get(key), locked, RETRY_KIND, key, config);
        RetryPolicy policy = instance(retries, key, k -> new RetryPolicy(k, resolved, environment));
        if (!policy.config().equals(resolved)) {
            throw conflict("Retry instance", key, policy.config(), resolved);
        }
        return policy;
    }


    /**
     * Returns the hedge policy for one call site, creating it on first use. Used by {@link Pipeline} and,
     * through {@link dev.resilience4cata.generated.Policies}, by generated code. Call sites with the same {@code name} share the instance, and so
     * its hedge budget.
     *
     * @param instanceId derived id of the call site, used when {@code name} is empty
     * @param name explicit instance name from the annotation, or {@code ""}
     * @param config named configuration to start from, or {@code ""}
     * @param overrides values set in the annotations, or {@code null}
     * @param locked {@code locked = true} on the annotation: the values set in {@code overrides} win over
     *     the instance's per-instance overrides
     * @return the policy
     * @throws IllegalStateException if two call sites share a name but resolve to different configurations
     */
    HedgePolicy hedgePolicy(String instanceId, String name, String config,
            @Nullable Consumer<HedgeConfig.Builder> overrides, boolean locked) {
        String key = name.isEmpty() ? instanceId : name;
        HedgeConfig resolved = resolve(hedgeConfig(config), overrides, instances.hedge().get(key), locked, HEDGE_KIND, key, config);
        HedgePolicy policy = instance(hedges, key, k -> new HedgePolicy(k, resolved, environment));
        if (!policy.config().equals(resolved)) {
            throw conflict("Hedge instance", key, policy.config(), resolved);
        }
        return policy;
    }

    /**
     * Returns the circuit breaker for one call site, creating it on first use. Used by {@link Pipeline}
     * and, through {@link dev.resilience4cata.generated.Policies}, by generated code.
     *
     * @param instanceId derived id of the call site, used when {@code name} is empty
     * @param name explicit instance name from the annotation, or {@code ""}
     * @param config named configuration to start from, or {@code ""}
     * @param overrides values set in the annotations, or {@code null}
     * @param locked {@code locked = true} on the annotation: the values set in {@code overrides} win over
     *     the instance's per-instance overrides
     * @return the circuit breaker
     * @throws IllegalStateException if two call sites share a name but resolve to different configurations
     */
    CircuitBreakerPolicy circuitBreakerPolicy(String instanceId, String name, String config,
            @Nullable Consumer<CircuitBreakerConfig.Builder> overrides, boolean locked) {
        String key = name.isEmpty() ? instanceId : name;
        CircuitBreakerConfig resolved = resolve(circuitBreakerConfig(config), overrides, instances.circuitBreaker().get(key), locked, CIRCUIT_BREAKER_KIND, key, config);
        CircuitBreakerPolicy policy = instance(circuitBreakers, key, k -> new CircuitBreakerPolicy(k, resolved, environment));
        if (!policy.config().equals(resolved)) {
            throw conflict("Circuit breaker", key, policy.config(), resolved);
        }
        return policy;
    }


    /**
     * Returns the rate limiter for one call site, creating it on first use. Used by {@link Pipeline} and,
     * through {@link dev.resilience4cata.generated.Policies}, by generated code.
     *
     * @param instanceId derived id of the call site, used when {@code name} is empty
     * @param name explicit instance name from the annotation, or {@code ""}
     * @param config named configuration to start from, or {@code ""}
     * @param overrides values set in the annotations, or {@code null}
     * @param locked {@code locked = true} on the annotation: the values set in {@code overrides} win over
     *     the instance's per-instance overrides
     * @return the rate limiter
     * @throws IllegalStateException if two call sites share a name but resolve to different configurations
     */
    RateLimiterPolicy rateLimiterPolicy(String instanceId, String name, String config,
            @Nullable Consumer<RateLimiterConfig.Builder> overrides, boolean locked) {
        String key = name.isEmpty() ? instanceId : name;
        RateLimiterConfig resolved = resolve(rateLimiterConfig(config), overrides, instances.rateLimiter().get(key), locked, RATE_LIMITER_KIND, key, config);
        RateLimiterPolicy policy = instance(rateLimiters, key, k -> new RateLimiterPolicy(k, resolved, environment));
        if (!policy.config().equals(resolved)) {
            throw conflict("Rate limiter", key, policy.config(), resolved);
        }
        return policy;
    }


    /**
     * Returns the timeout policy for one call site. Used by {@link Pipeline} and, through
     * {@link dev.resilience4cata.generated.Policies}, by generated code. Timeouts hold no state shared between calls,
     * so every call site gets its own policy; the id under which a snapshot shows it is {@code name}, or
     * {@code instanceId} when it is empty.
     *
     * @param instanceId derived id of the call site, used when {@code name} is empty
     * @param name explicit instance name from the annotation, or {@code ""}
     * @param config named configuration to start from, or {@code ""}
     * @param overrides values set in the annotations, or {@code null}
     * @param locked {@code locked = true} on the annotation: the values set in {@code overrides} win over
     *     the instance's per-instance overrides
     * @return the policy
     */
    TimeoutPolicy timeoutPolicy(String instanceId, String name, String config,
            @Nullable Consumer<TimeoutConfig.Builder> overrides, boolean locked) {
        String key = name.isEmpty() ? instanceId : name;
        TimeoutConfig resolved = resolve(timeoutConfig(config), overrides, instances.timeout().get(key), locked, TIMEOUT_KIND, key, config);
        TimeoutPolicy policy = new TimeoutPolicy(key, resolved, environment);
        if (timeouts.putIfAbsent(key, policy) == null) { // only for snapshot(): the first call site with this id is shown
            instanceCreated();
        }
        return policy;
    }

    /**
     * Returns the fallback policy for one method. Fallbacks hold no state, so every method gets its own policy;
     * the registry only adds its event listeners.
     *
     * @param instanceId id of the method, {@code <interface>#<method>(<parameter types>)}
     * @param applyOn failures that get the fallback
     * @param skipOn failures that never get the fallback; wins over {@code applyOn}
     * @param taken the exception types of sibling fallback methods that do not take every failure (a failure
     *     that is none of them is not handled, so no {@code FallbackEvent.Applied} is published for it), or
     *     {@code null} when the fallback takes every failure that {@code applyOn} and {@code skipOn} let through
     * @return the policy
     */
    FallbackPolicy fallbackPolicy(String instanceId, List<Class<? extends Throwable>> applyOn,
            List<Class<? extends Throwable>> skipOn, @Nullable List<Class<? extends Throwable>> taken) {
        return taken == null ? new FallbackPolicy(instanceId, applyOn, skipOn, environment)
                : new FallbackPolicy(instanceId, applyOn, skipOn, taken, environment);
    }


    /**
     * Returns the bulkhead for one call site, creating it on first use. Used by {@link Pipeline} and,
     * through {@link dev.resilience4cata.generated.Policies}, by generated code.
     *
     * @param instanceId derived id of the call site, used when {@code name} is empty
     * @param name explicit instance name from the annotation, or {@code ""}
     * @param config named configuration to start from, or {@code ""}
     * @param overrides values set in the annotations, or {@code null}
     * @param locked {@code locked = true} on the annotation: the values set in {@code overrides} win over
     *     the instance's per-instance overrides
     * @return the bulkhead
     * @throws IllegalStateException if two call sites share a name but resolve to different configurations
     */
    BulkheadPolicy bulkheadPolicy(String instanceId, String name, String config,
            @Nullable Consumer<BulkheadConfig.Builder> overrides, boolean locked) {
        String key = name.isEmpty() ? instanceId : name;
        BulkheadConfig resolved = resolve(bulkheadConfig(config), overrides, instances.bulkhead().get(key), locked, BULKHEAD_KIND, key, config);
        BulkheadPolicy policy = instance(bulkheads, key, k -> new BulkheadPolicy(k, resolved, environment));
        if (!policy.config().equals(resolved)) {
            throw conflict("Bulkhead", key, policy.config(), resolved);
        }
        return policy;
    }

    /**
     * Returns a bulkhead, to inspect it. Bulkheads are created when an annotated interface is wrapped.
     *
     * @param id the bulkhead's {@code name}, or its derived id {@code <interface>#<method>(<parameter types>)}
     * @return the bulkhead
     * @throws IllegalArgumentException if no bulkhead has that id
     */
    public BulkheadHandle bulkhead(String id) {
        BulkheadHandle handle = bulkheads.get(id);
        if (handle == null) {
            throw unknownInstance(BULKHEAD_NAME, id, bulkheads.keySet());
        }
        return handle;
    }


    /**
     * Returns the adaptive limit for one call site, creating it on first use. Used by {@link Pipeline} and,
     * through {@link dev.resilience4cata.generated.Policies}, by generated code.
     *
     * @param instanceId derived id of the call site, used when {@code name} is empty
     * @param name explicit instance name from the annotation, or {@code ""}
     * @param config named configuration to start from, or {@code ""}
     * @param overrides values set in the annotations, or {@code null}
     * @param locked {@code locked = true} on the annotation: the values set in {@code overrides} win over
     *     the instance's per-instance overrides
     * @return the adaptive limit
     * @throws IllegalStateException if two call sites share a name but resolve to different configurations
     */
    AdaptiveLimitPolicy adaptiveLimitPolicy(String instanceId, String name, String config,
            @Nullable Consumer<AdaptiveLimitConfig.Builder> overrides, boolean locked) {
        String key = name.isEmpty() ? instanceId : name;
        AdaptiveLimitConfig resolved = resolve(adaptiveLimitConfig(config), overrides, instances.adaptiveLimit().get(key), locked,
                ADAPTIVE_LIMIT_KIND, key, config);
        AdaptiveLimitPolicy policy = instance(adaptiveLimits, key, k -> new AdaptiveLimitPolicy(k, resolved, environment));
        if (!policy.config().equals(resolved)) {
            throw conflict("Adaptive limit", key, policy.config(), resolved);
        }
        return policy;
    }

    /**
     * Returns an adaptive limit, to inspect it. Adaptive limits are created when an annotated interface is
     * wrapped.
     *
     * @param id the adaptive limit's {@code name}, or its derived id {@code <interface>#<method>(<parameter types>)}
     * @return the adaptive limit
     * @throws IllegalArgumentException if no adaptive limit has that id
     */
    public AdaptiveLimitHandle adaptiveLimit(String id) {
        AdaptiveLimitHandle handle = adaptiveLimits.get(id);
        if (handle == null) {
            throw unknownInstance("adaptive limit", id, adaptiveLimits.keySet());
        }
        return handle;
    }

    /**
     * Returns a rate limiter, to inspect it. Rate limiters are created when an annotated interface is wrapped.
     *
     * @param id the rate limiter's {@code name}, or its derived id {@code <interface>#<method>(<parameter types>)}
     * @return the rate limiter
     * @throws IllegalArgumentException if no rate limiter has that id
     */
    public RateLimiterHandle rateLimiter(String id) {
        RateLimiterHandle handle = rateLimiters.get(id);
        if (handle == null) {
            throw unknownInstance("rate limiter", id, rateLimiters.keySet());
        }
        return handle;
    }

    /**
     * Returns a circuit breaker, to inspect or control it. Breakers are created when an annotated
     * interface is wrapped with {@code Resilient.of(...)}, so this only finds them afterwards.
     *
     * @param id the breaker's {@code name}, or its derived id {@code <interface>#<method>(<parameter types>)}
     * @return the breaker
     * @throws IllegalArgumentException if no breaker has that id
     */
    public CircuitBreakerHandle circuitBreaker(String id) {
        CircuitBreakerHandle handle = circuitBreakers.get(id);
        if (handle == null) {
            throw unknownInstance("circuit breaker", id, circuitBreakers.keySet());
        }
        return handle;
    }

    private static IllegalArgumentException unknownInstance(String what, String id, Set<String> known) {
        return new IllegalArgumentException(ErrorCode.UNKNOWN_INSTANCE.message("No " + what + " '" + id
                + "' in this registry." + Suggestions.didYouMean(id, known) + " Known: " + new TreeSet<>(known)
                + ". Use the name set in the annotation (or the pipeline id), or the derived id"
                + " <interface>#<method>(<parameter types>); an instance exists only once its interface has been"
                + " wrapped with Resilient.of(...), its class created with Resilient.create(...), or its pipeline built."));
    }

    /**
     * Runs {@code action} each time a new policy instance appears in {@link #snapshot()}, from now on:
     * once per instance, on the thread that creates it ({@code Resilient.of}, {@code Resilient.create}
     * or {@code Pipeline.Builder.build()}), after it is visible through {@link #snapshot()} and the
     * handle lookups. Meant for metrics bridges that register meters for instances created after they
     * were bound. Instances are created when services are wrapped, never during a call, so this adds
     * nothing to the calls. An exception thrown by {@code action} does not reach the code that created
     * the instance: it goes to the current thread's uncaught exception handler.
     *
     * @param action what to run; it may read the registry (for example {@link #snapshot()})
     * @return a registration whose {@link Registration#close()} stops the action
     */
    public Registration onInstanceCreated(Runnable action) {
        Objects.requireNonNull(action, "action");
        Runnable registered = action::run; // a distinct object, so that close() removes this registration only
        CopyOnWriteArrayList<Runnable> listeners = creationListeners; // the registration must not hold the registry
        listeners.add(registered);
        return () -> listeners.remove(registered);
    }

    /** A registration made with {@link #onInstanceCreated(Runnable)}. */
    @FunctionalInterface
    public interface Registration extends AutoCloseable {

        /** Stops the action; calling it again does nothing. */
        @Override
        void close();
    }

    /** Returns the instance for {@code key}, creating it and telling {@link #onInstanceCreated} listeners if new. */
    private <P> P instance(ConcurrentHashMap<String, P> policies, String key, Function<String, P> create) {
        P existing = policies.get(key);
        if (existing != null) {
            return existing;
        }
        boolean[] created = new boolean[1];
        P policy = policies.computeIfAbsent(key, k -> {
            created[0] = true;
            return create.apply(k);
        });
        if (created[0]) {
            instanceCreated();
        }
        return policy;
    }

    // java:S1181: a failing listener, whatever it throws (except a VirtualMachineError), must not fail the
    // creation of the instance: its failure goes to the uncaught exception handler instead
    @SuppressWarnings("java:S1181")
    private void instanceCreated() {
        for (Runnable listener : creationListeners) {
            try {
                listener.run();
            } catch (VirtualMachineError e) {
                throw e;
            } catch (Throwable e) {
                Thread thread = Thread.currentThread();
                try {
                    thread.getUncaughtExceptionHandler().uncaughtException(thread, e);
                } catch (Throwable ignored) {
                    // The handler failed too: creating the instance must still succeed.
                }
            }
        }
    }

    /** Returns every circuit breaker created so far, sorted by id. */
    public List<CircuitBreakerHandle> circuitBreakers() {
        return circuitBreakers.values().stream()
                .sorted(Comparator.comparing(CircuitBreakerHandle::id))
                .map(CircuitBreakerHandle.class::cast)
                .toList();
    }

    /**
     * Reads the state of every policy instance created so far. Allocates and walks the circuit
     * breakers' windows, so it is meant for health checks and metrics scrapes, not for every call; it
     * adds nothing to the calls themselves.
     *
     * @return the snapshot, each list sorted by instance id
     */
    public ResilienceSnapshot snapshot() {
        return new ResilienceSnapshot(
                sorted(circuitBreakers, CircuitBreakerPolicy::snapshot, CircuitBreakerSnapshot::instance),
                sorted(retries, (String id, RetryPolicy retry) -> new RetrySnapshot(id, retry.config(),
                        retry.budgetSnapshot()),
                        RetrySnapshot::instance),
                sorted(rateLimiters, RateLimiterPolicy::snapshot, RateLimiterSnapshot::instance),
                sorted(timeouts, (String id, TimeoutPolicy timeout) -> new TimeoutSnapshot(id, timeout.config()),
                        TimeoutSnapshot::instance),
                sorted(bulkheads, BulkheadPolicy::snapshot, BulkheadSnapshot::instance),
                sorted(hedges, (String id, HedgePolicy hedge) -> new HedgeSnapshot(id, hedge.config(),
                        hedge.budgetSnapshot()), HedgeSnapshot::instance),
                sorted(adaptiveLimits, AdaptiveLimitPolicy::snapshot, AdaptiveLimitSnapshot::instance));
    }

    private static <P, S> List<S> sorted(Map<String, P> policies, java.util.function.Function<P, S> snapshot,
            java.util.function.Function<S, String> id) {
        return sorted(policies, (String key, P policy) -> snapshot.apply(policy), id);
    }

    private static <P, S> List<S> sorted(Map<String, P> policies, java.util.function.BiFunction<String, P, S> snapshot,
            java.util.function.Function<S, String> id) {
        List<S> result = new ArrayList<>(policies.size());
        policies.forEach((key, policy) -> result.add(snapshot.apply(key, policy)));
        result.sort(Comparator.comparing(id));
        return result;
    }

    /** Builder for {@link ResilienceRegistry}. */
    public static final class Builder {
        private @Nullable Consumer<RetryConfig.Builder> retryDefaults;
        private final Map<String, Consumer<RetryConfig.Builder>> retryConfigs = new LinkedHashMap<>();
        private @Nullable Consumer<HedgeConfig.Builder> hedgeDefaults;
        private final Map<String, Consumer<HedgeConfig.Builder>> hedgeConfigs = new LinkedHashMap<>();
        private final Map<String, Consumer<HedgeConfig.Builder>> hedgeOverlays = new LinkedHashMap<>();
        private final Map<String, Consumer<HedgeConfig.Builder>> hedgeInstances = new LinkedHashMap<>();
        private @Nullable Consumer<CircuitBreakerConfig.Builder> circuitBreakerDefaults;
        private final Map<String, Consumer<CircuitBreakerConfig.Builder>> circuitBreakerConfigs = new LinkedHashMap<>();
        private @Nullable Consumer<RateLimiterConfig.Builder> rateLimiterDefaults;
        private final Map<String, Consumer<RateLimiterConfig.Builder>> rateLimiterConfigs = new LinkedHashMap<>();
        private @Nullable Consumer<TimeoutConfig.Builder> timeoutDefaults;
        private final Map<String, Consumer<TimeoutConfig.Builder>> timeoutConfigs = new LinkedHashMap<>();
        private @Nullable Consumer<BulkheadConfig.Builder> bulkheadDefaults;
        private final Map<String, Consumer<BulkheadConfig.Builder>> bulkheadConfigs = new LinkedHashMap<>();
        private final Map<String, Consumer<RetryConfig.Builder>> retryOverlays = new LinkedHashMap<>();
        private final Map<String, Consumer<RetryConfig.Builder>> retryInstances = new LinkedHashMap<>();
        private final Map<String, Consumer<CircuitBreakerConfig.Builder>> circuitBreakerOverlays = new LinkedHashMap<>();
        private final Map<String, Consumer<CircuitBreakerConfig.Builder>> circuitBreakerInstances = new LinkedHashMap<>();
        private final Map<String, Consumer<RateLimiterConfig.Builder>> rateLimiterOverlays = new LinkedHashMap<>();
        private final Map<String, Consumer<RateLimiterConfig.Builder>> rateLimiterInstances = new LinkedHashMap<>();
        private final Map<String, Consumer<TimeoutConfig.Builder>> timeoutOverlays = new LinkedHashMap<>();
        private final Map<String, Consumer<TimeoutConfig.Builder>> timeoutInstances = new LinkedHashMap<>();
        private final Map<String, Consumer<BulkheadConfig.Builder>> bulkheadOverlays = new LinkedHashMap<>();
        private final Map<String, Consumer<BulkheadConfig.Builder>> bulkheadInstances = new LinkedHashMap<>();
        private @Nullable Consumer<AdaptiveLimitConfig.Builder> adaptiveLimitDefaults;
        private final Map<String, Consumer<AdaptiveLimitConfig.Builder>> adaptiveLimitConfigs = new LinkedHashMap<>();
        private final Map<String, Consumer<AdaptiveLimitConfig.Builder>> adaptiveLimitOverlays = new LinkedHashMap<>();
        private final Map<String, Consumer<AdaptiveLimitConfig.Builder>> adaptiveLimitInstances = new LinkedHashMap<>();
        private final List<EventDispatcher.Subscription> listeners = new ArrayList<>();
        private @Nullable Ticker ticker;
        private @Nullable RandomGenerator random;

        private Builder() {
        }

        /**
         * Registers a listener for one event type on every policy instance. Listeners are fixed when the
         * registry is built: each policy looks up its listeners once, when it is created, so an event
         * type nobody listens to costs nothing on the call path. Listeners run synchronously on the
         * thread that caused the event; see {@link EventListener}.
         *
         * <pre>{@code
         * builder.listen(CircuitBreakerEvent.StateTransition.class, t -> alerts.send(t.instance() + " is " + t.to()))
         *        .listen(RetryEvent.class, e -> log.debug("{}", e));
         * }</pre>
         *
         * @param type an event record (such as {@code CircuitBreakerEvent.StateTransition}), a policy's
         *     event interface (such as {@code RetryEvent}), or {@code ResilienceEvent} for every event
         * @param listener the listener
         * @param <E> the event type
         * @return this builder
         */
        public <E extends ResilienceEvent> Builder listen(Class<E> type, EventListener<? super E> listener) {
            listeners.add(new EventDispatcher.Subscription(type, null, listener));
            return this;
        }

        /**
         * Registers a listener for one event type on one policy instance only. See
         * {@link #listen(Class, EventListener)}.
         *
         * @param instance the instance id: the {@code name} in the annotation, or the derived
         *     {@code <interface>#<method>(<parameter types>)}
         * @param type the event type
         * @param listener the listener
         * @param <E> the event type
         * @return this builder
         */
        public <E extends ResilienceEvent> Builder listen(String instance, Class<E> type,
                EventListener<? super E> listener) {
            Objects.requireNonNull(instance, "instance");
            listeners.add(new EventDispatcher.Subscription(type, instance, listener));
            return this;
        }

        /**
         * Changes the retry defaults, which every named configuration and annotation starts from.
         *
         * @param defaults applied to a builder that starts from {@link RetryConfig#DEFAULTS}
         * @return this builder
         */
        public Builder retryDefaults(Consumer<RetryConfig.Builder> defaults) {
            this.retryDefaults = Objects.requireNonNull(defaults, DEFAULTS);
            return this;
        }

        /**
         * Registers a named retry configuration, referenced from {@code @Retry(config = "name")}.
         *
         * @param name the name; must not be empty or {@code "default"}
         * @param config applied to a builder that starts from the retry defaults
         * @return this builder
         */
        public Builder retry(String name, Consumer<RetryConfig.Builder> config) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(config, CONFIG);
            register(retryConfigs, name, config, RETRY_NAME);
            return this;
        }

        /**
         * Overrides attributes of the named retry configuration {@code name}: applied after
         * {@link #retry(String, Consumer)} whatever the order of the calls or, if {@code name} is not
         * registered with it, on top of the retry defaults, which defines {@code name}. Several calls for
         * the same name apply in call order. Meant for external configuration, which overrides code
         * attribute by attribute: {@code ResilienceConfig.applyTo(...)} registers {@code configs.<name>.*}
         * with it.
         *
         * @param name the name; must not be empty or {@code "default"}
         * @param overlay applied after the programmatic configuration of {@code name}, if any
         * @return this builder
         */
        public Builder retryOverlay(String name, Consumer<RetryConfig.Builder> overlay) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(overlay, OVERLAY);
            compose(retryOverlays, checkedName(name, RETRY_NAME, OVERLAY_METHOD), overlay);
            return this;
        }

        /**
         * Overrides attributes of one retry instance, after its named configuration and the values set in
         * its annotation (or pipeline step). Applied once, when the instance is created; an id that no
         * instance uses is ignored. Several calls for the same id apply in call order, so calls made
         * after {@code ResilienceConfig.applyTo(...)} win over its {@code instances.<id>.*} keys.
         *
         * @param id the instance id: the {@code name} in the annotation (or the pipeline id), or the
         *     derived {@code <interface>#<method>(<parameter types>)}
         * @param overrides applied last when the instance's configuration is resolved
         * @return this builder
         */
        public Builder retryInstance(String id, Consumer<RetryConfig.Builder> overrides) {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(overrides, OVERRIDES);
            compose(retryInstances, checkedId(id, RETRY_NAME), overrides);
            return this;
        }

        /**
         * Changes the hedge defaults, which every named configuration and annotation starts from.
         *
         * @param defaults applied to a builder that starts from {@link HedgeConfig#DEFAULTS}
         * @return this builder
         */
        public Builder hedgeDefaults(Consumer<HedgeConfig.Builder> defaults) {
            this.hedgeDefaults = Objects.requireNonNull(defaults, DEFAULTS);
            return this;
        }

        /**
         * Registers a named hedge configuration, referenced from {@code @Hedge(config = "name")}.
         *
         * @param name the name; must not be empty or {@code "default"}
         * @param config applied to a builder that starts from the hedge defaults
         * @return this builder
         */
        public Builder hedge(String name, Consumer<HedgeConfig.Builder> config) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(config, CONFIG);
            register(hedgeConfigs, name, config, HEDGE_NAME);
            return this;
        }

        /**
         * Overrides attributes of the named hedge configuration {@code name}: applied after
         * {@link #hedge(String, Consumer)} whatever the order of the calls or, if {@code name} is not
         * registered with it, on top of the hedge defaults, which defines {@code name}. Several calls for
         * the same name apply in call order. Meant for external configuration, which overrides code
         * attribute by attribute: {@code ResilienceConfig.applyTo(...)} registers {@code configs.<name>.*}
         * with it.
         *
         * @param name the name; must not be empty or {@code "default"}
         * @param overlay applied after the programmatic configuration of {@code name}, if any
         * @return this builder
         */
        public Builder hedgeOverlay(String name, Consumer<HedgeConfig.Builder> overlay) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(overlay, OVERLAY);
            compose(hedgeOverlays, checkedName(name, HEDGE_NAME, OVERLAY_METHOD), overlay);
            return this;
        }

        /**
         * Overrides attributes of one hedge instance, after its named configuration and the values set in
         * its annotation (or pipeline step). Applied once, when the instance is created; an id that no
         * instance uses is ignored. Several calls for the same id apply in call order, so calls made
         * after {@code ResilienceConfig.applyTo(...)} win over its {@code instances.<id>.*} keys.
         *
         * @param id the instance id: the {@code name} in the annotation (or the pipeline id), or the
         *     derived {@code <interface>#<method>(<parameter types>)}
         * @param overrides applied last when the instance's configuration is resolved
         * @return this builder
         */
        public Builder hedgeInstance(String id, Consumer<HedgeConfig.Builder> overrides) {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(overrides, OVERRIDES);
            compose(hedgeInstances, checkedId(id, HEDGE_NAME), overrides);
            return this;
        }

        /**
         * Changes the circuit breaker defaults, which every named configuration and annotation starts from.
         *
         * @param defaults applied to a builder that starts from {@link CircuitBreakerConfig#DEFAULTS}
         * @return this builder
         */
        public Builder circuitBreakerDefaults(Consumer<CircuitBreakerConfig.Builder> defaults) {
            this.circuitBreakerDefaults = Objects.requireNonNull(defaults, DEFAULTS);
            return this;
        }

        /**
         * Registers a named circuit breaker configuration, referenced from
         * {@code @CircuitBreaker(config = "name")}.
         *
         * @param name the name; must not be empty or {@code "default"}
         * @param config applied to a builder that starts from the circuit breaker defaults
         * @return this builder
         */
        public Builder circuitBreaker(String name, Consumer<CircuitBreakerConfig.Builder> config) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(config, CONFIG);
            register(circuitBreakerConfigs, name, config, CIRCUIT_BREAKER_NAME);
            return this;
        }

        /**
         * Overrides attributes of the named circuit breaker configuration {@code name}: applied after
         * {@link #circuitBreaker(String, Consumer)} whatever the order of the calls or, if {@code name} is not
         * registered with it, on top of the circuit breaker defaults, which defines {@code name}. Several calls for
         * the same name apply in call order. Meant for external configuration, which overrides code
         * attribute by attribute: {@code ResilienceConfig.applyTo(...)} registers {@code configs.<name>.*}
         * with it.
         *
         * @param name the name; must not be empty or {@code "default"}
         * @param overlay applied after the programmatic configuration of {@code name}, if any
         * @return this builder
         */
        public Builder circuitBreakerOverlay(String name, Consumer<CircuitBreakerConfig.Builder> overlay) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(overlay, OVERLAY);
            compose(circuitBreakerOverlays, checkedName(name, CIRCUIT_BREAKER_NAME, OVERLAY_METHOD), overlay);
            return this;
        }

        /**
         * Overrides attributes of one circuit breaker instance, after its named configuration and the values set in
         * its annotation (or pipeline step). Applied once, when the instance is created; an id that no
         * instance uses is ignored. Several calls for the same id apply in call order, so calls made
         * after {@code ResilienceConfig.applyTo(...)} win over its {@code instances.<id>.*} keys.
         *
         * @param id the instance id: the {@code name} in the annotation (or the pipeline id), or the
         *     derived {@code <interface>#<method>(<parameter types>)}
         * @param overrides applied last when the instance's configuration is resolved
         * @return this builder
         */
        public Builder circuitBreakerInstance(String id, Consumer<CircuitBreakerConfig.Builder> overrides) {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(overrides, OVERRIDES);
            compose(circuitBreakerInstances, checkedId(id, CIRCUIT_BREAKER_NAME), overrides);
            return this;
        }

        /**
         * Changes the rate limiter defaults, which every named configuration and annotation starts from.
         *
         * @param defaults applied to a builder that starts from {@link RateLimiterConfig#DEFAULTS}
         * @return this builder
         */
        public Builder rateLimiterDefaults(Consumer<RateLimiterConfig.Builder> defaults) {
            this.rateLimiterDefaults = Objects.requireNonNull(defaults, DEFAULTS);
            return this;
        }

        /**
         * Registers a named rate limiter configuration, referenced from {@code @RateLimiter(config = "name")}.
         *
         * @param name the name; must not be empty or {@code "default"}
         * @param config applied to a builder that starts from the rate limiter defaults
         * @return this builder
         */
        public Builder rateLimiter(String name, Consumer<RateLimiterConfig.Builder> config) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(config, CONFIG);
            register(rateLimiterConfigs, name, config, RATE_LIMITER_NAME);
            return this;
        }

        /**
         * Overrides attributes of the named rate limiter configuration {@code name}: applied after
         * {@link #rateLimiter(String, Consumer)} whatever the order of the calls or, if {@code name} is not
         * registered with it, on top of the rate limiter defaults, which defines {@code name}. Several calls for
         * the same name apply in call order. Meant for external configuration, which overrides code
         * attribute by attribute: {@code ResilienceConfig.applyTo(...)} registers {@code configs.<name>.*}
         * with it.
         *
         * @param name the name; must not be empty or {@code "default"}
         * @param overlay applied after the programmatic configuration of {@code name}, if any
         * @return this builder
         */
        public Builder rateLimiterOverlay(String name, Consumer<RateLimiterConfig.Builder> overlay) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(overlay, OVERLAY);
            compose(rateLimiterOverlays, checkedName(name, RATE_LIMITER_NAME, OVERLAY_METHOD), overlay);
            return this;
        }

        /**
         * Overrides attributes of one rate limiter instance, after its named configuration and the values set in
         * its annotation (or pipeline step). Applied once, when the instance is created; an id that no
         * instance uses is ignored. Several calls for the same id apply in call order, so calls made
         * after {@code ResilienceConfig.applyTo(...)} win over its {@code instances.<id>.*} keys.
         *
         * @param id the instance id: the {@code name} in the annotation (or the pipeline id), or the
         *     derived {@code <interface>#<method>(<parameter types>)}
         * @param overrides applied last when the instance's configuration is resolved
         * @return this builder
         */
        public Builder rateLimiterInstance(String id, Consumer<RateLimiterConfig.Builder> overrides) {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(overrides, OVERRIDES);
            compose(rateLimiterInstances, checkedId(id, RATE_LIMITER_NAME), overrides);
            return this;
        }

        /**
         * Changes the timeout defaults, which every named configuration and annotation starts from.
         *
         * @param defaults applied to a builder that starts from {@link TimeoutConfig#DEFAULTS}
         * @return this builder
         */
        public Builder timeoutDefaults(Consumer<TimeoutConfig.Builder> defaults) {
            this.timeoutDefaults = Objects.requireNonNull(defaults, DEFAULTS);
            return this;
        }

        /**
         * Registers a named timeout configuration, referenced from {@code @Timeout(config = "name")}.
         *
         * @param name the name; must not be empty or {@code "default"}
         * @param config applied to a builder that starts from the timeout defaults
         * @return this builder
         */
        public Builder timeout(String name, Consumer<TimeoutConfig.Builder> config) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(config, CONFIG);
            register(timeoutConfigs, name, config, TIMEOUT_NAME);
            return this;
        }

        /**
         * Overrides attributes of the named timeout configuration {@code name}: applied after
         * {@link #timeout(String, Consumer)} whatever the order of the calls or, if {@code name} is not
         * registered with it, on top of the timeout defaults, which defines {@code name}. Several calls for
         * the same name apply in call order. Meant for external configuration, which overrides code
         * attribute by attribute: {@code ResilienceConfig.applyTo(...)} registers {@code configs.<name>.*}
         * with it.
         *
         * @param name the name; must not be empty or {@code "default"}
         * @param overlay applied after the programmatic configuration of {@code name}, if any
         * @return this builder
         */
        public Builder timeoutOverlay(String name, Consumer<TimeoutConfig.Builder> overlay) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(overlay, OVERLAY);
            compose(timeoutOverlays, checkedName(name, TIMEOUT_NAME, OVERLAY_METHOD), overlay);
            return this;
        }

        /**
         * Overrides attributes of one timeout instance, after its named configuration and the values set in
         * its annotation (or pipeline step). Applied once, when the instance is created; an id that no
         * instance uses is ignored. Several calls for the same id apply in call order, so calls made
         * after {@code ResilienceConfig.applyTo(...)} win over its {@code instances.<id>.*} keys.
         *
         * @param id the instance id: the {@code name} in the annotation (or the pipeline id), or the
         *     derived {@code <interface>#<method>(<parameter types>)}
         * @param overrides applied last when the instance's configuration is resolved
         * @return this builder
         */
        public Builder timeoutInstance(String id, Consumer<TimeoutConfig.Builder> overrides) {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(overrides, OVERRIDES);
            compose(timeoutInstances, checkedId(id, TIMEOUT_NAME), overrides);
            return this;
        }

        /**
         * Changes the bulkhead defaults, which every named configuration and annotation starts from.
         *
         * @param defaults applied to a builder that starts from {@link BulkheadConfig#DEFAULTS}
         * @return this builder
         */
        public Builder bulkheadDefaults(Consumer<BulkheadConfig.Builder> defaults) {
            this.bulkheadDefaults = Objects.requireNonNull(defaults, DEFAULTS);
            return this;
        }

        /**
         * Registers a named bulkhead configuration, referenced from {@code @Bulkhead(config = "name")}.
         *
         * @param name the name; must not be empty or {@code "default"}
         * @param config applied to a builder that starts from the bulkhead defaults
         * @return this builder
         */
        public Builder bulkhead(String name, Consumer<BulkheadConfig.Builder> config) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(config, CONFIG);
            register(bulkheadConfigs, name, config, BULKHEAD_NAME);
            return this;
        }

        /**
         * Overrides attributes of the named bulkhead configuration {@code name}: applied after
         * {@link #bulkhead(String, Consumer)} whatever the order of the calls or, if {@code name} is not
         * registered with it, on top of the bulkhead defaults, which defines {@code name}. Several calls for
         * the same name apply in call order. Meant for external configuration, which overrides code
         * attribute by attribute: {@code ResilienceConfig.applyTo(...)} registers {@code configs.<name>.*}
         * with it.
         *
         * @param name the name; must not be empty or {@code "default"}
         * @param overlay applied after the programmatic configuration of {@code name}, if any
         * @return this builder
         */
        public Builder bulkheadOverlay(String name, Consumer<BulkheadConfig.Builder> overlay) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(overlay, OVERLAY);
            compose(bulkheadOverlays, checkedName(name, BULKHEAD_NAME, OVERLAY_METHOD), overlay);
            return this;
        }

        /**
         * Overrides attributes of one bulkhead instance, after its named configuration and the values set in
         * its annotation (or pipeline step). Applied once, when the instance is created; an id that no
         * instance uses is ignored. Several calls for the same id apply in call order, so calls made
         * after {@code ResilienceConfig.applyTo(...)} win over its {@code instances.<id>.*} keys.
         *
         * @param id the instance id: the {@code name} in the annotation (or the pipeline id), or the
         *     derived {@code <interface>#<method>(<parameter types>)}
         * @param overrides applied last when the instance's configuration is resolved
         * @return this builder
         */
        public Builder bulkheadInstance(String id, Consumer<BulkheadConfig.Builder> overrides) {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(overrides, OVERRIDES);
            compose(bulkheadInstances, checkedId(id, BULKHEAD_NAME), overrides);
            return this;
        }

        /**
         * Changes the adaptive limit defaults, which every named configuration and annotation starts from.
         *
         * @param defaults applied to a builder that starts from {@link AdaptiveLimitConfig#DEFAULTS}
         * @return this builder
         */
        public Builder adaptiveLimitDefaults(Consumer<AdaptiveLimitConfig.Builder> defaults) {
            this.adaptiveLimitDefaults = Objects.requireNonNull(defaults, DEFAULTS);
            return this;
        }

        /**
         * Registers a named adaptive limit configuration, referenced from {@code @AdaptiveLimit(config = "name")}.
         *
         * @param name the name; must not be empty or {@code "default"}
         * @param config applied to a builder that starts from the adaptive limit defaults
         * @return this builder
         */
        public Builder adaptiveLimit(String name, Consumer<AdaptiveLimitConfig.Builder> config) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(config, CONFIG);
            register(adaptiveLimitConfigs, name, config, ADAPTIVE_LIMIT_NAME);
            return this;
        }

        /**
         * Overrides attributes of the named adaptive limit configuration {@code name}: applied after
         * {@link #adaptiveLimit(String, Consumer)} whatever the order of the calls or, if {@code name} is not
         * registered with it, on top of the adaptive limit defaults, which defines {@code name}. Several calls
         * for the same name apply in call order. Meant for external configuration, which overrides code
         * attribute by attribute: {@code ResilienceConfig.applyTo(...)} registers {@code configs.<name>.*}
         * with it.
         *
         * @param name the name; must not be empty or {@code "default"}
         * @param overlay applied after the programmatic configuration of {@code name}, if any
         * @return this builder
         */
        public Builder adaptiveLimitOverlay(String name, Consumer<AdaptiveLimitConfig.Builder> overlay) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(overlay, OVERLAY);
            compose(adaptiveLimitOverlays, checkedName(name, ADAPTIVE_LIMIT_NAME, OVERLAY_METHOD), overlay);
            return this;
        }

        /**
         * Overrides attributes of one adaptive limit instance, after its named configuration and the values set
         * in its annotation (or pipeline step). Applied once, when the instance is created; an id that no
         * instance uses is ignored. Several calls for the same id apply in call order, so calls made
         * after {@code ResilienceConfig.applyTo(...)} win over its {@code instances.<id>.*} keys.
         *
         * @param id the instance id: the {@code name} in the annotation (or the pipeline id), or the
         *     derived {@code <interface>#<method>(<parameter types>)}
         * @param overrides applied last when the instance's configuration is resolved
         * @return this builder
         */
        public Builder adaptiveLimitInstance(String id, Consumer<AdaptiveLimitConfig.Builder> overrides) {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(overrides, OVERRIDES);
            compose(adaptiveLimitInstances, checkedId(id, ADAPTIVE_LIMIT_NAME), overrides);
            return this;
        }

        /**
         * Replaces the system clock, {@code Thread.sleep} and the library's timer threads with
         * {@code ticker} for every policy this registry creates. Meant for tests: the
         * {@code resilience4cata-testkit} module's {@code ManualClock} makes retries with backoff,
         * breaker waits, rate limits and timeouts follow a clock the test moves by hand.
         *
         * <p>A synchronous bulkhead wait ({@code maxWait > 0}) also ends on the ticker: the waiting caller
         * gets a slot as soon as one is released, and is rejected once the ticker passes {@code maxWait}.
         *
         * @param ticker the time source
         * @return this builder
         */
        public Builder ticker(Ticker ticker) {
            this.ticker = Objects.requireNonNull(ticker, "ticker");
            return this;
        }

        /**
         * Draws retry jitter from {@code random} instead of {@code ThreadLocalRandom}, so that delays are
         * reproducible, e.g. {@code new java.util.Random(42)}. It is called from the threads whose calls
         * fail, so it must be thread-safe if several threads can fail at once ({@code java.util.Random}
         * is, {@code SplittableRandom} is not); with several threads the order of the draws, and so which
         * call gets which delay, is not deterministic.
         *
         * @param random the generator
         * @return this builder
         */
        public Builder random(RandomGenerator random) {
            this.random = Objects.requireNonNull(random, "random");
            return this;
        }

        private static <B> void register(Map<String, Consumer<B>> configs, String name, Consumer<B> config, String kind) {
            checkedName(name, kind, "");
            if (configs.putIfAbsent(name, config) != null) {
                throw new IllegalArgumentException(ErrorCode.DUPLICATE_CONFIG.message("The " + kind + CONFIG_QUOTE
                        + name + "' is registered twice with ResilienceRegistry.Builder." + kind + "(\"" + name
                        + "\", ...). Register it once; to change some of its attributes elsewhere, use "
                        + kind + "Overlay(\"" + name + "\", ...)."));
            }
        }

        private static String checkedName(String name, String kind, String method) {
            if (name.isEmpty() || name.equals(DEFAULT)) {
                throw new IllegalArgumentException(ErrorCode.RESERVED_CONFIG_NAME.message("ResilienceRegistry.Builder."
                        + kind + method + "(\"" + name + "\", ...): \"\" and \"default\" are reserved, they mean the "
                        + kind + " defaults. Use " + kind + "Defaults(...) to change the defaults, or give the config"
                        + " another name."));
            }
            return name;
        }

        private static String checkedId(String id, String kind) {
            if (id.isEmpty()) {
                throw new IllegalArgumentException(ErrorCode.EMPTY_INSTANCE_ID.message("ResilienceRegistry.Builder."
                        + kind + "Instance(\"\", ...): the instance id must not be empty. Pass the name set in the"
                        + " annotation (or the pipeline id), or the derived id <interface>#<method>(<parameter types>)."));
            }
            return id;
        }

        private static <B> void compose(Map<String, Consumer<B>> map, String key, Consumer<B> consumer) {
            map.merge(key, consumer, Consumer::andThen);
        }

        /** The programmatic configuration of each name, followed by its overlay. */
        private static <B> Map<String, Consumer<B>> withOverlays(Map<String, Consumer<B>> configs,
                Map<String, Consumer<B>> overlays) {
            Map<String, Consumer<B>> all = new LinkedHashMap<>(configs);
            overlays.forEach((name, overlay) -> all.merge(name, overlay, Consumer::andThen));
            return all;
        }

        /**
         * Resolves and validates every configuration.
         *
         * @return the registry
         * @throws IllegalArgumentException if a configuration is invalid
         */
        public ResilienceRegistry build() {
            RetryConfig retry = retry(RetryConfig.DEFAULTS, retryDefaults, DEFAULT);
            Map<String, RetryConfig> retries = new LinkedHashMap<>();
            withOverlays(retryConfigs, retryOverlays).forEach((name, config) -> retries.put(name, retry(retry, config, name)));
            HedgeConfig hedge = hedge(HedgeConfig.DEFAULTS, hedgeDefaults, DEFAULT);
            Map<String, HedgeConfig> hedges = new LinkedHashMap<>();
            withOverlays(hedgeConfigs, hedgeOverlays).forEach((name, config) -> hedges.put(name, hedge(hedge, config, name)));
            CircuitBreakerConfig circuitBreaker = circuitBreaker(CircuitBreakerConfig.DEFAULTS, circuitBreakerDefaults, DEFAULT);
            Map<String, CircuitBreakerConfig> circuitBreakers = new LinkedHashMap<>();
            withOverlays(circuitBreakerConfigs, circuitBreakerOverlays).forEach((name, config) -> circuitBreakers.put(name, circuitBreaker(circuitBreaker, config, name)));
            RateLimiterConfig rateLimiter = rateLimiter(RateLimiterConfig.DEFAULTS, rateLimiterDefaults, DEFAULT);
            Map<String, RateLimiterConfig> rateLimiters = new LinkedHashMap<>();
            withOverlays(rateLimiterConfigs, rateLimiterOverlays).forEach((name, config) -> rateLimiters.put(name, rateLimiter(rateLimiter, config, name)));
            TimeoutConfig timeout = timeout(TimeoutConfig.DEFAULTS, timeoutDefaults, DEFAULT);
            Map<String, TimeoutConfig> timeouts = new LinkedHashMap<>();
            withOverlays(timeoutConfigs, timeoutOverlays).forEach((name, config) -> timeouts.put(name, timeout(timeout, config, name)));
            BulkheadConfig bulkhead = bulkhead(BulkheadConfig.DEFAULTS, bulkheadDefaults, DEFAULT);
            Map<String, BulkheadConfig> bulkheads = new LinkedHashMap<>();
            withOverlays(bulkheadConfigs, bulkheadOverlays).forEach((name, config) -> bulkheads.put(name, bulkhead(bulkhead, config, name)));
            AdaptiveLimitConfig adaptiveLimit = adaptiveLimit(AdaptiveLimitConfig.DEFAULTS, adaptiveLimitDefaults, DEFAULT);
            Map<String, AdaptiveLimitConfig> adaptiveLimits = new LinkedHashMap<>();
            withOverlays(adaptiveLimitConfigs, adaptiveLimitOverlays)
                    .forEach((name, config) -> adaptiveLimits.put(name, adaptiveLimit(adaptiveLimit, config, name)));
            return new ResilienceRegistry(new Configs<>(retry, retries), new Configs<>(hedge, hedges),
                    new Configs<>(circuitBreaker, circuitBreakers),
                    new Configs<>(rateLimiter, rateLimiters), new Configs<>(timeout, timeouts),
                    new Configs<>(bulkhead, bulkheads), new Configs<>(adaptiveLimit, adaptiveLimits),
                    PolicyEnvironment.of(ticker, random).withEvents(EventDispatcher.of(listeners)),
                    new Instances(retryInstances, hedgeInstances, circuitBreakerInstances, rateLimiterInstances, timeoutInstances,
                            bulkheadInstances, adaptiveLimitInstances));
        }

        private static IllegalArgumentException invalidNamed(String kind, String name, IllegalArgumentException e) {
            String where = name.equals(DEFAULT)
                    ? kind + " defaults: " + ErrorCode.detail(e) + ". Fix the value set with " + kind
                            + "Defaults(...) (defaults." + kind + ".* in external configuration)."
                    : kind + CONFIG_QUOTE + name + "': " + ErrorCode.detail(e) + ". It starts from the " + kind
                            + " defaults; fix the value set with " + kind + "(\"" + name + "\", ...) or " + kind
                            + "Overlay(\"" + name + "\", ...) (configs." + name + "." + kind
                            + ".* in external configuration).";
            return new IllegalArgumentException(ErrorCode.INVALID_NAMED_CONFIG.message("Invalid " + where), e);
        }

        private static RateLimiterConfig rateLimiter(RateLimiterConfig base,
                @Nullable Consumer<RateLimiterConfig.Builder> config, String name) {
            if (config == null) {
                return base;
            }
            RateLimiterConfig.Builder builder = base.toBuilder();
            config.accept(builder);
            try {
                return builder.build();
            } catch (IllegalArgumentException e) {
                throw invalidNamed(RATE_LIMITER_NAME, name, e);
            }
        }

        private static BulkheadConfig bulkhead(BulkheadConfig base, @Nullable Consumer<BulkheadConfig.Builder> config, String name) {
            if (config == null) {
                return base;
            }
            BulkheadConfig.Builder builder = base.toBuilder();
            config.accept(builder);
            try {
                return builder.build();
            } catch (IllegalArgumentException e) {
                throw invalidNamed(BULKHEAD_NAME, name, e);
            }
        }

        private static AdaptiveLimitConfig adaptiveLimit(AdaptiveLimitConfig base,
                @Nullable Consumer<AdaptiveLimitConfig.Builder> config, String name) {
            if (config == null) {
                return base;
            }
            AdaptiveLimitConfig.Builder builder = base.toBuilder();
            config.accept(builder);
            try {
                return builder.build();
            } catch (IllegalArgumentException e) {
                throw invalidNamed(ADAPTIVE_LIMIT_NAME, name, e);
            }
        }

        private static TimeoutConfig timeout(TimeoutConfig base, @Nullable Consumer<TimeoutConfig.Builder> config, String name) {
            if (config == null) {
                return base;
            }
            TimeoutConfig.Builder builder = base.toBuilder();
            config.accept(builder);
            try {
                return builder.build();
            } catch (IllegalArgumentException e) {
                throw invalidNamed(TIMEOUT_NAME, name, e);
            }
        }

        private static HedgeConfig hedge(HedgeConfig base, @Nullable Consumer<HedgeConfig.Builder> config, String name) {
            if (config == null) {
                return base;
            }
            HedgeConfig.Builder builder = base.toBuilder();
            config.accept(builder);
            try {
                return builder.build();
            } catch (IllegalArgumentException e) {
                throw invalidNamed(HEDGE_NAME, name, e);
            }
        }

        private static RetryConfig retry(RetryConfig base, @Nullable Consumer<RetryConfig.Builder> config, String name) {
            if (config == null) {
                return base;
            }
            RetryConfig.Builder builder = base.toBuilder();
            config.accept(builder);
            try {
                return builder.build();
            } catch (IllegalArgumentException e) {
                throw invalidNamed(RETRY_NAME, name, e);
            }
        }

        private static CircuitBreakerConfig circuitBreaker(CircuitBreakerConfig base,
                @Nullable Consumer<CircuitBreakerConfig.Builder> config, String name) {
            if (config == null) {
                return base;
            }
            CircuitBreakerConfig.Builder builder = base.toBuilder();
            config.accept(builder);
            try {
                return builder.build();
            } catch (IllegalArgumentException e) {
                throw invalidNamed(CIRCUIT_BREAKER_NAME, name, e);
            }
        }
    }

    /**
     * Lets {@link dev.resilience4cata.generated.Policies}, which generated code calls, reach the package-private
     * policy factories above, so that they stay out of this class's public API.
     */
    private static final class Access extends RegistryAccess {

        @Override
        public RetryPolicy retryPolicy(ResilienceRegistry registry, String instanceId, String name, String config,
                @Nullable Consumer<RetryConfig.Builder> overrides, boolean locked) {
            return registry.retryPolicy(instanceId, name, config, overrides, locked);
        }

        @Override
        public HedgePolicy hedgePolicy(ResilienceRegistry registry, String instanceId, String name, String config,
                @Nullable Consumer<HedgeConfig.Builder> overrides, boolean locked) {
            return registry.hedgePolicy(instanceId, name, config, overrides, locked);
        }

        @Override
        public CircuitBreakerPolicy circuitBreakerPolicy(ResilienceRegistry registry, String instanceId, String name,
                String config, @Nullable Consumer<CircuitBreakerConfig.Builder> overrides, boolean locked) {
            return registry.circuitBreakerPolicy(instanceId, name, config, overrides, locked);
        }

        @Override
        public RateLimiterPolicy rateLimiterPolicy(ResilienceRegistry registry, String instanceId, String name,
                String config, @Nullable Consumer<RateLimiterConfig.Builder> overrides, boolean locked) {
            return registry.rateLimiterPolicy(instanceId, name, config, overrides, locked);
        }

        @Override
        public AdaptiveLimitPolicy adaptiveLimitPolicy(ResilienceRegistry registry, String instanceId, String name,
                String config, @Nullable Consumer<AdaptiveLimitConfig.Builder> overrides, boolean locked) {
            return registry.adaptiveLimitPolicy(instanceId, name, config, overrides, locked);
        }

        @Override
        public TimeoutPolicy timeoutPolicy(ResilienceRegistry registry, String instanceId, String name, String config,
                @Nullable Consumer<TimeoutConfig.Builder> overrides, boolean locked) {
            return registry.timeoutPolicy(instanceId, name, config, overrides, locked);
        }

        @Override
        public BulkheadPolicy bulkheadPolicy(ResilienceRegistry registry, String instanceId, String name,
                String config, @Nullable Consumer<BulkheadConfig.Builder> overrides, boolean locked) {
            return registry.bulkheadPolicy(instanceId, name, config, overrides, locked);
        }

        @Override
        public FallbackPolicy fallbackPolicy(ResilienceRegistry registry, String instanceId,
                List<Class<? extends Throwable>> applyOn, List<Class<? extends Throwable>> skipOn,
                @Nullable List<Class<? extends Throwable>> taken) {
            return registry.fallbackPolicy(instanceId, applyOn, skipOn, taken);
        }

        @Override
        public IllegalStateException runtimeTooOld(String component, Class<?> generated, int level, int supported) {
            return new IllegalStateException(ErrorCode.RUNTIME_TOO_OLD.message(generated.getName()
                    + " was generated by a newer resilience4cata-processor than this " + component + " supports"
                    + " (generated-code level " + level + ", supported up to " + supported + "). Use a " + component
                    + " at least as new as the resilience4cata-processor that compiled it: import one"
                    + " resilience4cata-bom version for every resilience4cata artifact, or check which version your"
                    + " dependency tree resolves."));
        }
    }
}
