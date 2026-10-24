package dev.resilience4cata;

import dev.resilience4cata.generated.CircuitBreakerPolicy;
import dev.resilience4cata.generated.RetryPolicy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
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

    private ResilienceRegistry(RetryConfig retryDefaults, Map<String, RetryConfig> retryConfigs,
            CircuitBreakerConfig circuitBreakerDefaults, Map<String, CircuitBreakerConfig> circuitBreakerConfigs) {
        this.retryDefaults = retryDefaults;
        this.retryConfigs = Map.copyOf(retryConfigs);
        this.circuitBreakerDefaults = circuitBreakerDefaults;
        this.circuitBreakerConfigs = Map.copyOf(circuitBreakerConfigs);
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
        RetryPolicy policy = retries.computeIfAbsent(key, k -> new RetryPolicy(resolved));
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
        CircuitBreakerPolicy policy = circuitBreakers.computeIfAbsent(key, k -> new CircuitBreakerPolicy(k, resolved));
        if (!policy.config().equals(resolved)) {
            throw new IllegalStateException("Circuit breaker '" + key
                    + "' is used with two different configurations:\n  " + policy.config() + "\n  " + resolved
                    + "\nGive the call sites different names, or make their configuration identical.");
        }
        return policy;
    }

    /** Builder for {@link ResilienceRegistry}. */
    public static final class Builder {
        private @Nullable Consumer<RetryConfig.Builder> retryDefaults;
        private final Map<String, Consumer<RetryConfig.Builder>> retryConfigs = new LinkedHashMap<>();
        private @Nullable Consumer<CircuitBreakerConfig.Builder> circuitBreakerDefaults;
        private final Map<String, Consumer<CircuitBreakerConfig.Builder>> circuitBreakerConfigs = new LinkedHashMap<>();

        private Builder() {
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
            return new ResilienceRegistry(retry, retries, circuitBreaker, circuitBreakers);
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
