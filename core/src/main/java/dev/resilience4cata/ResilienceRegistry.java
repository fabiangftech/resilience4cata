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
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
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

    private ResilienceRegistry(RetryConfig retryDefaults, Map<String, RetryConfig> retryConfigs,
            CircuitBreakerConfig circuitBreakerDefaults, Map<String, CircuitBreakerConfig> circuitBreakerConfigs,
            RateLimiterConfig rateLimiterDefaults, Map<String, RateLimiterConfig> rateLimiterConfigs,
            TimeoutConfig timeoutDefaults, Map<String, TimeoutConfig> timeoutConfigs,
            BulkheadConfig bulkheadDefaults, Map<String, BulkheadConfig> bulkheadConfigs, PolicyEnvironment environment) {
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
            throw new IllegalArgumentException("Unknown " + kind + " config '" + config + "'. Known configs: "
                    + new TreeSet<>(named.keySet()) + ". Register it with ResilienceRegistry.builder()." + kind
                    + "(\"" + config + "\", c -> ...)");
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
        RetryConfig base = retryConfig(config);
        RetryConfig resolved;
        if (overrides == null) {
            resolved = base;
        } else {
            RetryConfig.Builder builder = base.toBuilder();
            overrides.accept(builder);
            resolved = builder.build();
        }
        String key = name.isEmpty() ? instanceId : name;
        RetryPolicy policy = retries.computeIfAbsent(key, k -> new RetryPolicy(k, resolved, environment));
        if (!policy.config().equals(resolved)) {
            throw new IllegalStateException("Retry instance '" + key
                    + "' is used with two different configurations:\n  " + policy.config() + "\n  " + resolved
                    + "\nGive the call sites different names, or make their configuration identical.");
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
        CircuitBreakerConfig base = circuitBreakerConfig(config);
        CircuitBreakerConfig resolved;
        if (overrides == null) {
            resolved = base;
        } else {
            CircuitBreakerConfig.Builder builder = base.toBuilder();
            overrides.accept(builder);
            resolved = builder.build();
        }
        String key = name.isEmpty() ? instanceId : name;
        CircuitBreakerPolicy policy = circuitBreakers.computeIfAbsent(key, k -> new CircuitBreakerPolicy(k, resolved, environment));
        if (!policy.config().equals(resolved)) {
            throw new IllegalStateException("Circuit breaker '" + key
                    + "' is used with two different configurations:\n  " + policy.config() + "\n  " + resolved
                    + "\nGive the call sites different names, or make their configuration identical.");
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
        RateLimiterConfig base = rateLimiterConfig(config);
        RateLimiterConfig resolved;
        if (overrides == null) {
            resolved = base;
        } else {
            RateLimiterConfig.Builder builder = base.toBuilder();
            overrides.accept(builder);
            resolved = builder.build();
        }
        String key = name.isEmpty() ? instanceId : name;
        RateLimiterPolicy policy = rateLimiters.computeIfAbsent(key, k -> new RateLimiterPolicy(k, resolved, environment));
        if (!policy.config().equals(resolved)) {
            throw new IllegalStateException("Rate limiter '" + key
                    + "' is used with two different configurations:\n  " + policy.config() + "\n  " + resolved
                    + "\nGive the call sites different names, or make their configuration identical.");
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
        TimeoutConfig base = timeoutConfig(config);
        TimeoutConfig resolved;
        if (overrides == null) {
            resolved = base;
        } else {
            TimeoutConfig.Builder builder = base.toBuilder();
            overrides.accept(builder);
            resolved = builder.build();
        }
        String key = name.isEmpty() ? instanceId : name;
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
        BulkheadConfig base = bulkheadConfig(config);
        BulkheadConfig resolved;
        if (overrides == null) {
            resolved = base;
        } else {
            BulkheadConfig.Builder builder = base.toBuilder();
            overrides.accept(builder);
            resolved = builder.build();
        }
        String key = name.isEmpty() ? instanceId : name;
        BulkheadPolicy policy = bulkheads.computeIfAbsent(key, k -> new BulkheadPolicy(k, resolved, environment));
        if (!policy.config().equals(resolved)) {
            throw new IllegalStateException("Bulkhead '" + key
                    + "' is used with two different configurations:\n  " + policy.config() + "\n  " + resolved
                    + "\nGive the call sites different names, or make their configuration identical.");
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
            throw new IllegalArgumentException("No bulkhead '" + id + "'. Known: " + new TreeSet<>(bulkheads.keySet())
                    + ". Bulkheads exist once their interface has been wrapped with Resilient.of(...).");
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
            throw new IllegalArgumentException("No rate limiter '" + id + "'. Known: "
                    + new TreeSet<>(rateLimiters.keySet())
                    + ". Rate limiters exist once their interface has been wrapped with Resilient.of(...).");
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
            throw new IllegalArgumentException("No circuit breaker '" + id + "'. Known: "
                    + new TreeSet<>(circuitBreakers.keySet())
                    + ". Breakers exist once their interface has been wrapped with Resilient.of(...).");
        }
        return handle;
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
         * Replaces the system clock, {@code Thread.sleep} and the library's timer threads with
         * {@code ticker} for every policy this registry creates. Meant for tests: the
         * {@code resilience4cata-testkit} module's {@code ManualClock} makes retries with backoff,
         * breaker waits, rate limits and timeouts follow a clock the test moves by hand.
         *
         * <p>Not supported: the synchronous wait of a bulkhead with {@code maxWait > 0}, which still
         * waits in real time.
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
            if (name.isEmpty() || name.equals(DEFAULT)) {
                throw new IllegalArgumentException("Use " + kind + "Defaults(...) to change the default " + kind + " config");
            }
            if (configs.putIfAbsent(name, config) != null) {
                throw new IllegalArgumentException(kind + " config '" + name + "' is registered twice");
            }
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
            retryConfigs.forEach((name, config) -> retries.put(name, retry(retry, config, name)));
            CircuitBreakerConfig circuitBreaker = circuitBreaker(CircuitBreakerConfig.DEFAULTS, circuitBreakerDefaults, DEFAULT);
            Map<String, CircuitBreakerConfig> circuitBreakers = new LinkedHashMap<>();
            circuitBreakerConfigs.forEach((name, config) -> circuitBreakers.put(name, circuitBreaker(circuitBreaker, config, name)));
            RateLimiterConfig rateLimiter = rateLimiter(RateLimiterConfig.DEFAULTS, rateLimiterDefaults, DEFAULT);
            Map<String, RateLimiterConfig> rateLimiters = new LinkedHashMap<>();
            rateLimiterConfigs.forEach((name, config) -> rateLimiters.put(name, rateLimiter(rateLimiter, config, name)));
            TimeoutConfig timeout = timeout(TimeoutConfig.DEFAULTS, timeoutDefaults, DEFAULT);
            Map<String, TimeoutConfig> timeouts = new LinkedHashMap<>();
            timeoutConfigs.forEach((name, config) -> timeouts.put(name, timeout(timeout, config, name)));
            BulkheadConfig bulkhead = bulkhead(BulkheadConfig.DEFAULTS, bulkheadDefaults, DEFAULT);
            Map<String, BulkheadConfig> bulkheads = new LinkedHashMap<>();
            bulkheadConfigs.forEach((name, config) -> bulkheads.put(name, bulkhead(bulkhead, config, name)));
            return new ResilienceRegistry(retry, retries, circuitBreaker, circuitBreakers, rateLimiter, rateLimiters,
                    timeout, timeouts, bulkhead, bulkheads, PolicyEnvironment.of(ticker, random).withEvents(EventDispatcher.of(listeners)));
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
                throw new IllegalArgumentException("Invalid rateLimiter config '" + name + "': " + e.getMessage(), e);
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
                throw new IllegalArgumentException("Invalid bulkhead config '" + name + "': " + e.getMessage(), e);
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
                throw new IllegalArgumentException("Invalid timeout config '" + name + "': " + e.getMessage(), e);
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
                throw new IllegalArgumentException("Invalid retry config '" + name + "': " + e.getMessage(), e);
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
                throw new IllegalArgumentException("Invalid circuitBreaker config '" + name + "': " + e.getMessage(), e);
            }
        }
    }
}
