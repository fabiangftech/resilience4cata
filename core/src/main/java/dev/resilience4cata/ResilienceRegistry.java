package dev.resilience4cata;

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
 *         .build();
 * }</pre>
 */
public final class ResilienceRegistry {

    private static final String DEFAULT = "default";

    private final RetryConfig retryDefaults;
    private final Map<String, RetryConfig> retryConfigs;
    private final ConcurrentHashMap<String, RetryPolicy> retries = new ConcurrentHashMap<>();

    private ResilienceRegistry(RetryConfig retryDefaults, Map<String, RetryConfig> retryConfigs) {
        this.retryDefaults = retryDefaults;
        this.retryConfigs = Map.copyOf(retryConfigs);
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
        if (config.isEmpty() || config.equals(DEFAULT)) {
            return retryDefaults;
        }
        RetryConfig found = retryConfigs.get(config);
        if (found == null) {
            throw new IllegalArgumentException("Unknown retry config '" + config + "'. Known configs: "
                    + new TreeSet<>(retryConfigs.keySet()) + ". Register it with ResilienceRegistry.builder().retry(\""
                    + config + "\", r -> ...)");
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

    /** Builder for {@link ResilienceRegistry}. */
    public static final class Builder {
        private @Nullable Consumer<RetryConfig.Builder> retryDefaults;
        private final Map<String, Consumer<RetryConfig.Builder>> retryConfigs = new LinkedHashMap<>();

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
            if (name.isEmpty() || name.equals(DEFAULT)) {
                throw new IllegalArgumentException("Use retryDefaults(...) to change the default retry config");
            }
            if (retryConfigs.putIfAbsent(name, config) != null) {
                throw new IllegalArgumentException("Retry config '" + name + "' is registered twice");
            }
            return this;
        }

        /**
         * Resolves and validates every configuration.
         *
         * @return the registry
         * @throws IllegalArgumentException if a configuration is invalid
         */
        public ResilienceRegistry build() {
            RetryConfig defaults = apply(RetryConfig.DEFAULTS, retryDefaults, DEFAULT);
            Map<String, RetryConfig> named = new LinkedHashMap<>();
            retryConfigs.forEach((name, config) -> named.put(name, apply(defaults, config, name)));
            return new ResilienceRegistry(defaults, named);
        }

        private static RetryConfig apply(RetryConfig base, @Nullable Consumer<RetryConfig.Builder> config, String name) {
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
    }
}
