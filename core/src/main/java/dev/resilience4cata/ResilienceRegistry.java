package dev.resilience4cata;

import dev.resilience4cata.generated.BulkheadPolicy;
import dev.resilience4cata.generated.CircuitBreakerPolicy;
import dev.resilience4cata.generated.EventDispatcher;
import dev.resilience4cata.generated.FallbackPolicy;
import dev.resilience4cata.generated.PolicyEnvironment;
import dev.resilience4cata.generated.RateLimiterPolicy;
import dev.resilience4cata.generated.RetryPolicy;
import dev.resilience4cata.generated.TimeoutPolicy;
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
public final class ResilienceRegistry {

    private static final String DEFAULT = "default";

    private final RetryConfig retryDefaults;
    private final Map<String, RetryConfig> retryConfigs;
    private final ConcurrentHashMap<String, RetryPolicy> retries = new ConcurrentHashMap<>();
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
    private final PolicyEnvironment environment;
    private final Instances instances;

    private ResilienceRegistry(RetryConfig retryDefaults, Map<String, RetryConfig> retryConfigs,
            CircuitBreakerConfig circuitBreakerDefaults, Map<String, CircuitBreakerConfig> circuitBreakerConfigs,
            RateLimiterConfig rateLimiterDefaults, Map<String, RateLimiterConfig> rateLimiterConfigs,
            TimeoutConfig timeoutDefaults, Map<String, TimeoutConfig> timeoutConfigs,
            BulkheadConfig bulkheadDefaults, Map<String, BulkheadConfig> bulkheadConfigs, PolicyEnvironment environment,
            Instances instances) {
        this.retryDefaults = retryDefaults;
        this.retryConfigs = Map.copyOf(retryConfigs);
        this.circuitBreakerDefaults = circuitBreakerDefaults;
        this.circuitBreakerConfigs = Map.copyOf(circuitBreakerConfigs);
        this.rateLimiterDefaults = rateLimiterDefaults;
        this.rateLimiterConfigs = Map.copyOf(rateLimiterConfigs);
        this.timeoutDefaults = timeoutDefaults;
        this.timeoutConfigs = Map.copyOf(timeoutConfigs);
        this.bulkheadDefaults = bulkheadDefaults;
        this.bulkheadConfigs = Map.copyOf(bulkheadConfigs);
        this.environment = environment;
        this.instances = instances;
    }

    /** Per-instance overrides ({@link Builder#retryInstance}, ...), by instance id. Immutable. */
    private record Instances(Map<String, Consumer<RetryConfig.Builder>> retry,
            Map<String, Consumer<CircuitBreakerConfig.Builder>> circuitBreaker,
            Map<String, Consumer<RateLimiterConfig.Builder>> rateLimiter,
            Map<String, Consumer<TimeoutConfig.Builder>> timeout,
            Map<String, Consumer<BulkheadConfig.Builder>> bulkhead) {
        Instances {
            retry = Map.copyOf(retry);
            circuitBreaker = Map.copyOf(circuitBreaker);
            rateLimiter = Map.copyOf(rateLimiter);
            timeout = Map.copyOf(timeout);
            bulkhead = Map.copyOf(bulkhead);
        }
    }

    /**
     * Resolves one instance's configuration: the named config ({@code base}), then the annotation's or
     * pipeline step's values ({@code overrides}), then the instance's own overrides ({@code instance}).
     * Runs once, when the instance is created.
     */
    private static <C, B> C resolve(C base, @Nullable Consumer<B> overrides, @Nullable Consumer<B> instance,
            Function<C, B> toBuilder, Function<B, C> build, String kind, String key, String config) {
        if (overrides == null && instance == null) {
            return base;
        }
        B builder = toBuilder.apply(base);
        if (overrides != null) {
            overrides.accept(builder);
        }
        if (instance != null) {
            instance.accept(builder);
        }
        try {
            return build.apply(builder);
        } catch (IllegalArgumentException e) {
            String named = config.isEmpty() || config.equals(DEFAULT) ? "the " + kind + " defaults"
                    : "named config '" + config + "'";
            String sources = instance == null
                    ? "the values set in its annotation or pipeline step"
                    : (overrides == null ? "" : "the values set in its annotation or pipeline step, then ")
                            + "its per-instance overrides (" + kind + "Instance(\"" + key + "\", ...), or instances."
                            + (key.matches("[A-Za-z0-9_-]+") ? key : '"' + key + '"') + "." + kind
                            + ".* in external configuration)";
            throw new IllegalArgumentException(ErrorCode.INVALID_INSTANCE_CONFIG.message("Invalid " + kind
                    + " config for instance '" + key + "': " + ErrorCode.detail(e) + ". It starts from " + named
                    + " and applies " + sources + "; fix the value there so that the combination is valid."), e);
        }
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
        return lookup(config, retryDefaults, retryConfigs, "retry");
    }

    /**
     * Returns a named circuit breaker configuration.
     *
     * @param config the name; {@code ""} or {@code "default"} means the defaults
     * @return the resolved configuration
     * @throws IllegalArgumentException if no configuration has that name
     */
    public CircuitBreakerConfig circuitBreakerConfig(String config) {
        return lookup(config, circuitBreakerDefaults, circuitBreakerConfigs, "circuitBreaker");
    }

    /**
     * Returns a named rate limiter configuration.
     *
     * @param config the name; {@code ""} or {@code "default"} means the defaults
     * @return the resolved configuration
     * @throws IllegalArgumentException if no configuration has that name
     */
    public RateLimiterConfig rateLimiterConfig(String config) {
        return lookup(config, rateLimiterDefaults, rateLimiterConfigs, "rateLimiter");
    }

    /**
     * Returns a named timeout configuration.
     *
     * @param config the name; {@code ""} or {@code "default"} means the defaults
     * @return the resolved configuration
     * @throws IllegalArgumentException if no configuration has that name
     */
    public TimeoutConfig timeoutConfig(String config) {
        return lookup(config, timeoutDefaults, timeoutConfigs, "timeout");
    }

    /**
     * Returns a named bulkhead configuration.
     *
     * @param config the name; {@code ""} or {@code "default"} means the defaults
     * @return the resolved configuration
     * @throws IllegalArgumentException if no configuration has that name
     */
    public BulkheadConfig bulkheadConfig(String config) {
        return lookup(config, bulkheadDefaults, bulkheadConfigs, "bulkhead");
    }

    private static <C> C lookup(String config, C defaults, Map<String, C> named, String kind) {
        if (config.isEmpty() || config.equals(DEFAULT)) {
            return defaults;
        }
        C found = named.get(config);
        if (found == null) {
            throw new IllegalArgumentException(ErrorCode.UNKNOWN_CONFIG.message("Unknown " + kind + " config '"
                    + config + "' (config = \"" + config + "\" in an annotation or pipeline step)."
                    + Suggestions.didYouMean(config, named.keySet()) + " Known " + kind + " configs: "
                    + new TreeSet<>(named.keySet()) + ". Register it with ResilienceRegistry.builder()." + kind
                    + "(\"" + config + "\", c -> ...), or with configs." + config + "." + kind
                    + ".* in external configuration."));
        }
        return found;
    }

    /**
     * Returns the retry policy for one call site, creating it on first use. Used by generated code;
     * not intended to be called directly.
     *
     * @param instanceId derived id of the call site, used when {@code name} is empty
     * @param name explicit instance name from the annotation, or {@code ""}
     * @param config named configuration to start from, or {@code ""}
     * @param overrides values set in the annotations, or {@code null}
     * @return the policy
     * @throws IllegalStateException if two call sites share a name but resolve to different configurations
     */
    public RetryPolicy retryPolicy(String instanceId, String name, String config,
            @Nullable Consumer<RetryConfig.Builder> overrides) {
        String key = name.isEmpty() ? instanceId : name;
        RetryConfig resolved = resolve(retryConfig(config), overrides, instances.retry().get(key), RetryConfig::toBuilder,
                RetryConfig.Builder::build, "retry", key, config);
        RetryPolicy policy = retries.computeIfAbsent(key, k -> new RetryPolicy(k, resolved, environment));
        if (!policy.config().equals(resolved)) {
            throw new IllegalStateException(ErrorCode.CONFLICTING_INSTANCE.message("Retry instance '" + key
                    + "' is used with two different configurations (a shared name, or the same pipeline id"
                    + " built twice):\n  " + policy.config() + "\n  " + resolved
                    + "\nGive the call sites different names, or make their configuration identical."));
        }
        return policy;
    }

    /**
     * Returns the circuit breaker for one call site, creating it on first use. Used by generated
     * code; not intended to be called directly.
     *
     * @param instanceId derived id of the call site, used when {@code name} is empty
     * @param name explicit instance name from the annotation, or {@code ""}
     * @param config named configuration to start from, or {@code ""}
     * @param overrides values set in the annotations, or {@code null}
     * @return the circuit breaker
     * @throws IllegalStateException if two call sites share a name but resolve to different configurations
     */
    public CircuitBreakerPolicy circuitBreakerPolicy(String instanceId, String name, String config,
            @Nullable Consumer<CircuitBreakerConfig.Builder> overrides) {
        String key = name.isEmpty() ? instanceId : name;
        CircuitBreakerConfig resolved = resolve(circuitBreakerConfig(config), overrides, instances.circuitBreaker().get(key), CircuitBreakerConfig::toBuilder,
                CircuitBreakerConfig.Builder::build, "circuitBreaker", key, config);
        CircuitBreakerPolicy policy = circuitBreakers.computeIfAbsent(key, k -> new CircuitBreakerPolicy(k, resolved, environment));
        if (!policy.config().equals(resolved)) {
            throw new IllegalStateException(ErrorCode.CONFLICTING_INSTANCE.message("Circuit breaker '" + key
                    + "' is used with two different configurations (a shared name, or the same pipeline id"
                    + " built twice):\n  " + policy.config() + "\n  " + resolved
                    + "\nGive the call sites different names, or make their configuration identical."));
        }
        return policy;
    }

    /**
     * Returns the rate limiter for one call site, creating it on first use. Used by generated code;
     * not intended to be called directly.
     *
     * @param instanceId derived id of the call site, used when {@code name} is empty
     * @param name explicit instance name from the annotation, or {@code ""}
     * @param config named configuration to start from, or {@code ""}
     * @param overrides values set in the annotations, or {@code null}
     * @return the rate limiter
     * @throws IllegalStateException if two call sites share a name but resolve to different configurations
     */
    public RateLimiterPolicy rateLimiterPolicy(String instanceId, String name, String config,
            @Nullable Consumer<RateLimiterConfig.Builder> overrides) {
        String key = name.isEmpty() ? instanceId : name;
        RateLimiterConfig resolved = resolve(rateLimiterConfig(config), overrides, instances.rateLimiter().get(key), RateLimiterConfig::toBuilder,
                RateLimiterConfig.Builder::build, "rateLimiter", key, config);
        RateLimiterPolicy policy = rateLimiters.computeIfAbsent(key, k -> new RateLimiterPolicy(k, resolved, environment));
        if (!policy.config().equals(resolved)) {
            throw new IllegalStateException(ErrorCode.CONFLICTING_INSTANCE.message("Rate limiter '" + key
                    + "' is used with two different configurations (a shared name, or the same pipeline id"
                    + " built twice):\n  " + policy.config() + "\n  " + resolved
                    + "\nGive the call sites different names, or make their configuration identical."));
        }
        return policy;
    }

    /**
     * Returns the timeout policy for one call site. Used by generated code; not intended to be called
     * directly. Timeouts hold no state shared between calls, so every call site gets its own policy.
     *
     * @param instanceId derived id of the call site
     * @param name unused: timeouts have no shared state; kept so that every policy has the same shape
     * @param config named configuration to start from, or {@code ""}
     * @param overrides values set in the annotations, or {@code null}
     * @return the policy
     */
    public TimeoutPolicy timeoutPolicy(String instanceId, String name, String config,
            @Nullable Consumer<TimeoutConfig.Builder> overrides) {
        String key = name.isEmpty() ? instanceId : name;
        TimeoutConfig resolved = resolve(timeoutConfig(config), overrides, instances.timeout().get(key), TimeoutConfig::toBuilder,
                TimeoutConfig.Builder::build, "timeout", key, config);
        TimeoutPolicy policy = new TimeoutPolicy(key, resolved, environment);
        timeouts.putIfAbsent(key, policy); // only for snapshot(): the first call site with this id is shown
        return policy;
    }

    /**
     * Returns the fallback policy for one method. Used by generated code; not intended to be called
     * directly. Fallbacks hold no state, so every method gets its own policy; the registry only adds
     * its event listeners.
     *
     * @param instanceId id of the method, {@code <interface>#<method>(<parameter types>)}
     * @param applyOn failures that get the fallback
     * @param skipOn failures that never get the fallback; wins over {@code applyOn}
     * @return the policy
     */
    public FallbackPolicy fallbackPolicy(String instanceId, List<Class<? extends Throwable>> applyOn,
            List<Class<? extends Throwable>> skipOn) {
        return new FallbackPolicy(instanceId, applyOn, skipOn, environment);
    }

    /**
     * Returns the fallback policy for a method whose sibling fallback methods do not take every failure.
     * Used by generated code; not intended to be called directly. A failure that is not an instance of
     * one of {@code taken} is not handled, so no {@code FallbackEvent.Applied} is published for it.
     *
     * @param instanceId id of the method, {@code <interface>#<method>(<parameter types>)}
     * @param applyOn failures that get the fallback
     * @param skipOn failures that never get the fallback; wins over {@code applyOn}
     * @param taken the exception types of the sibling fallback methods
     * @return the policy
     */
    public FallbackPolicy fallbackPolicy(String instanceId, List<Class<? extends Throwable>> applyOn,
            List<Class<? extends Throwable>> skipOn, List<Class<? extends Throwable>> taken) {
        return new FallbackPolicy(instanceId, applyOn, skipOn, taken, environment);
    }

    /**
     * Returns the bulkhead for one call site, creating it on first use. Used by generated code; not
     * intended to be called directly.
     *
     * @param instanceId derived id of the call site, used when {@code name} is empty
     * @param name explicit instance name from the annotation, or {@code ""}
     * @param config named configuration to start from, or {@code ""}
     * @param overrides values set in the annotations, or {@code null}
     * @return the bulkhead
     * @throws IllegalStateException if two call sites share a name but resolve to different configurations
     */
    public BulkheadPolicy bulkheadPolicy(String instanceId, String name, String config,
            @Nullable Consumer<BulkheadConfig.Builder> overrides) {
        String key = name.isEmpty() ? instanceId : name;
        BulkheadConfig resolved = resolve(bulkheadConfig(config), overrides, instances.bulkhead().get(key), BulkheadConfig::toBuilder,
                BulkheadConfig.Builder::build, "bulkhead", key, config);
        BulkheadPolicy policy = bulkheads.computeIfAbsent(key, k -> new BulkheadPolicy(k, resolved, environment));
        if (!policy.config().equals(resolved)) {
            throw new IllegalStateException(ErrorCode.CONFLICTING_INSTANCE.message("Bulkhead '" + key
                    + "' is used with two different configurations (a shared name, or the same pipeline id"
                    + " built twice):\n  " + policy.config() + "\n  " + resolved
                    + "\nGive the call sites different names, or make their configuration identical."));
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
            throw unknownInstance("bulkhead", id, bulkheads.keySet());
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
                sorted(retries, (String id, RetryPolicy retry) -> new RetrySnapshot(id, retry.config()),
                        RetrySnapshot::instance),
                sorted(rateLimiters, RateLimiterPolicy::snapshot, RateLimiterSnapshot::instance),
                sorted(timeouts, (String id, TimeoutPolicy timeout) -> new TimeoutSnapshot(id, timeout.config()),
                        TimeoutSnapshot::instance),
                sorted(bulkheads, BulkheadPolicy::snapshot, BulkheadSnapshot::instance));
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
            this.retryDefaults = Objects.requireNonNull(defaults, "defaults");
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
            Objects.requireNonNull(config, "config");
            register(retryConfigs, name, config, "retry");
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
            Objects.requireNonNull(overlay, "overlay");
            compose(retryOverlays, checkedName(name, "retry", "Overlay"), overlay);
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
            Objects.requireNonNull(overrides, "overrides");
            compose(retryInstances, checkedId(id, "retry"), overrides);
            return this;
        }

        /**
         * Changes the circuit breaker defaults, which every named configuration and annotation starts from.
         *
         * @param defaults applied to a builder that starts from {@link CircuitBreakerConfig#DEFAULTS}
         * @return this builder
         */
        public Builder circuitBreakerDefaults(Consumer<CircuitBreakerConfig.Builder> defaults) {
            this.circuitBreakerDefaults = Objects.requireNonNull(defaults, "defaults");
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
            Objects.requireNonNull(config, "config");
            register(circuitBreakerConfigs, name, config, "circuitBreaker");
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
            Objects.requireNonNull(overlay, "overlay");
            compose(circuitBreakerOverlays, checkedName(name, "circuitBreaker", "Overlay"), overlay);
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
            Objects.requireNonNull(overrides, "overrides");
            compose(circuitBreakerInstances, checkedId(id, "circuitBreaker"), overrides);
            return this;
        }

        /**
         * Changes the rate limiter defaults, which every named configuration and annotation starts from.
         *
         * @param defaults applied to a builder that starts from {@link RateLimiterConfig#DEFAULTS}
         * @return this builder
         */
        public Builder rateLimiterDefaults(Consumer<RateLimiterConfig.Builder> defaults) {
            this.rateLimiterDefaults = Objects.requireNonNull(defaults, "defaults");
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
            Objects.requireNonNull(config, "config");
            register(rateLimiterConfigs, name, config, "rateLimiter");
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
            Objects.requireNonNull(overlay, "overlay");
            compose(rateLimiterOverlays, checkedName(name, "rateLimiter", "Overlay"), overlay);
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
            Objects.requireNonNull(overrides, "overrides");
            compose(rateLimiterInstances, checkedId(id, "rateLimiter"), overrides);
            return this;
        }

        /**
         * Changes the timeout defaults, which every named configuration and annotation starts from.
         *
         * @param defaults applied to a builder that starts from {@link TimeoutConfig#DEFAULTS}
         * @return this builder
         */
        public Builder timeoutDefaults(Consumer<TimeoutConfig.Builder> defaults) {
            this.timeoutDefaults = Objects.requireNonNull(defaults, "defaults");
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
            Objects.requireNonNull(config, "config");
            register(timeoutConfigs, name, config, "timeout");
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
            Objects.requireNonNull(overlay, "overlay");
            compose(timeoutOverlays, checkedName(name, "timeout", "Overlay"), overlay);
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
            Objects.requireNonNull(overrides, "overrides");
            compose(timeoutInstances, checkedId(id, "timeout"), overrides);
            return this;
        }

        /**
         * Changes the bulkhead defaults, which every named configuration and annotation starts from.
         *
         * @param defaults applied to a builder that starts from {@link BulkheadConfig#DEFAULTS}
         * @return this builder
         */
        public Builder bulkheadDefaults(Consumer<BulkheadConfig.Builder> defaults) {
            this.bulkheadDefaults = Objects.requireNonNull(defaults, "defaults");
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
            Objects.requireNonNull(config, "config");
            register(bulkheadConfigs, name, config, "bulkhead");
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
            Objects.requireNonNull(overlay, "overlay");
            compose(bulkheadOverlays, checkedName(name, "bulkhead", "Overlay"), overlay);
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
            Objects.requireNonNull(overrides, "overrides");
            compose(bulkheadInstances, checkedId(id, "bulkhead"), overrides);
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
                throw new IllegalArgumentException(ErrorCode.DUPLICATE_CONFIG.message("The " + kind + " config '"
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
            return new ResilienceRegistry(retry, retries, circuitBreaker, circuitBreakers, rateLimiter, rateLimiters,
                    timeout, timeouts, bulkhead, bulkheads, PolicyEnvironment.of(ticker, random).withEvents(EventDispatcher.of(listeners)),
                    new Instances(retryInstances, circuitBreakerInstances, rateLimiterInstances, timeoutInstances,
                            bulkheadInstances));
        }

        private static IllegalArgumentException invalidNamed(String kind, String name, IllegalArgumentException e) {
            String where = name.equals(DEFAULT)
                    ? kind + " defaults: " + ErrorCode.detail(e) + ". Fix the value set with " + kind
                            + "Defaults(...) (defaults." + kind + ".* in external configuration)."
                    : kind + " config '" + name + "': " + ErrorCode.detail(e) + ". It starts from the " + kind
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
                throw invalidNamed("rateLimiter", name, e);
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
                throw invalidNamed("bulkhead", name, e);
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
                throw invalidNamed("timeout", name, e);
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
                throw invalidNamed("retry", name, e);
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
                throw invalidNamed("circuitBreaker", name, e);
            }
        }
    }
}
