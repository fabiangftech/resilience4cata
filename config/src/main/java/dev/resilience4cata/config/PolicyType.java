package dev.resilience4cata.config;

import dev.resilience4cata.BulkheadConfig;
import dev.resilience4cata.CircuitBreakerConfig;
import dev.resilience4cata.RateLimiterConfig;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.RetryConfig;
import dev.resilience4cata.TimeoutConfig;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A configurable policy: its key name, attributes, built-in defaults, and how it is registered on a
 * {@link ResilienceRegistry.Builder}.
 *
 * @param <B> the config builder type
 * @param <C> the config record type
 */
final class PolicyType<B, C> {

    /** Starts the message of a value rejected by a core {@code *Config} (code R4C-REG-001). */
    private static final Pattern INVALID_VALUE_PREFIX = Pattern.compile("^\\[R4C-REG-001\\] Invalid \\w+ config: ");

    private static final String ENABLED = "enabled";

    /** Registers a named configuration overlay or per-instance overrides. */
    interface NamedRegistrar<B> {
        void register(ResilienceRegistry.Builder registry, String name, Consumer<B> config);
    }

    static final PolicyType<RetryConfig.Builder, RetryConfig> RETRY = new PolicyType<>("retry",
            RetryConfig.DEFAULTS, RetryConfig::toBuilder, RetryConfig.Builder::build,
            ResilienceRegistry.Builder::retryDefaults, ResilienceRegistry.Builder::retryOverlay,
            ResilienceRegistry.Builder::retryInstance, List.of(
                    Attribute.bool(ENABLED, RetryConfig.Builder::enabled),
                    Attribute.integer("maxAttempts", RetryConfig.Builder::maxAttempts),
                    Attribute.duration("delay", RetryConfig.Builder::delay),
                    Attribute.enumeration("backoff", RetryConfig.Backoff.class, RetryConfig.Builder::backoff),
                    Attribute.real("multiplier", RetryConfig.Builder::multiplier),
                    Attribute.duration("maxDelay", RetryConfig.Builder::maxDelay),
                    Attribute.enumeration("jitter", RetryConfig.Jitter.class, RetryConfig.Builder::jitter),
                    Attribute.duration("maxDuration", RetryConfig.Builder::maxDuration),
                    Attribute.classes("retryOn", RetryConfig.Builder::retryOn),
                    Attribute.classes("abortOn", RetryConfig.Builder::abortOn),
                    Attribute.real("budgetPercent", RetryConfig.Builder::budgetPercent),
                    Attribute.integer("budgetMinRetriesPerSecond", RetryConfig.Builder::budgetMinRetriesPerSecond),
                    Attribute.duration("budgetWindow", RetryConfig.Builder::budgetWindow)));

    static final PolicyType<CircuitBreakerConfig.Builder, CircuitBreakerConfig> CIRCUIT_BREAKER = new PolicyType<>(
            "circuitBreaker", CircuitBreakerConfig.DEFAULTS, CircuitBreakerConfig::toBuilder,
            CircuitBreakerConfig.Builder::build, ResilienceRegistry.Builder::circuitBreakerDefaults,
            ResilienceRegistry.Builder::circuitBreakerOverlay,
            ResilienceRegistry.Builder::circuitBreakerInstance, List.of(
                    Attribute.bool(ENABLED, CircuitBreakerConfig.Builder::enabled),
                    Attribute.enumeration("windowType", CircuitBreakerConfig.WindowType.class,
                            CircuitBreakerConfig.Builder::windowType),
                    Attribute.integer("windowSize", CircuitBreakerConfig.Builder::windowSize),
                    Attribute.integer("minimumCalls", CircuitBreakerConfig.Builder::minimumCalls),
                    Attribute.decimal("failureRateThreshold", CircuitBreakerConfig.Builder::failureRateThreshold),
                    Attribute.decimal("slowCallRateThreshold", CircuitBreakerConfig.Builder::slowCallRateThreshold),
                    Attribute.duration("slowCallDuration", CircuitBreakerConfig.Builder::slowCallDuration),
                    Attribute.duration("waitInOpen", CircuitBreakerConfig.Builder::waitInOpen),
                    Attribute.integer("halfOpenCalls", CircuitBreakerConfig.Builder::halfOpenCalls),
                    Attribute.duration("maxWaitInHalfOpen", CircuitBreakerConfig.Builder::maxWaitInHalfOpen),
                    Attribute.classes("recordOn", CircuitBreakerConfig.Builder::recordOn),
                    Attribute.classes("ignoreOn", CircuitBreakerConfig.Builder::ignoreOn)));

    static final PolicyType<TimeoutConfig.Builder, TimeoutConfig> TIMEOUT = new PolicyType<>("timeout",
            TimeoutConfig.DEFAULTS, TimeoutConfig::toBuilder, TimeoutConfig.Builder::build,
            ResilienceRegistry.Builder::timeoutDefaults, ResilienceRegistry.Builder::timeoutOverlay,
            ResilienceRegistry.Builder::timeoutInstance, List.of(
                    Attribute.bool(ENABLED, TimeoutConfig.Builder::enabled),
                    Attribute.duration("value", "timeout", TimeoutConfig.Builder::timeout)));

    static final PolicyType<BulkheadConfig.Builder, BulkheadConfig> BULKHEAD = new PolicyType<>("bulkhead",
            BulkheadConfig.DEFAULTS, BulkheadConfig::toBuilder, BulkheadConfig.Builder::build,
            ResilienceRegistry.Builder::bulkheadDefaults, ResilienceRegistry.Builder::bulkheadOverlay,
            ResilienceRegistry.Builder::bulkheadInstance, List.of(
                    Attribute.bool(ENABLED, BulkheadConfig.Builder::enabled),
                    Attribute.integer("maxConcurrent", BulkheadConfig.Builder::maxConcurrent),
                    Attribute.duration("maxWait", BulkheadConfig.Builder::maxWait)));

    static final PolicyType<RateLimiterConfig.Builder, RateLimiterConfig> RATE_LIMITER = new PolicyType<>(
            "rateLimiter", RateLimiterConfig.DEFAULTS, RateLimiterConfig::toBuilder, RateLimiterConfig.Builder::build,
            ResilienceRegistry.Builder::rateLimiterDefaults, ResilienceRegistry.Builder::rateLimiterOverlay,
            ResilienceRegistry.Builder::rateLimiterInstance, List.of(
                    Attribute.bool(ENABLED, RateLimiterConfig.Builder::enabled),
                    Attribute.integer("permits", RateLimiterConfig.Builder::permits),
                    Attribute.duration("period", RateLimiterConfig.Builder::period),
                    Attribute.duration("maxWait", RateLimiterConfig.Builder::maxWait)));

    /** Every configurable policy, in nesting order. */
    static final List<PolicyType<?, ?>> ALL = List.of(RETRY, CIRCUIT_BREAKER, RATE_LIMITER, TIMEOUT, BULKHEAD);

    final String name;
    private final C builtIn;
    private final Function<C, B> toBuilder;
    private final Function<B, C> build;
    private final BiConsumer<ResilienceRegistry.Builder, Consumer<B>> defaultsRegistrar;
    private final NamedRegistrar<B> namedRegistrar;
    private final NamedRegistrar<B> instanceRegistrar;
    final Map<String, Attribute<B>> attributes = new LinkedHashMap<>();

    @SuppressWarnings("java:S107") // java:S107: one descriptor per policy, built once in the constants above
    private PolicyType(String name, C builtIn, Function<C, B> toBuilder, Function<B, C> build,
            BiConsumer<ResilienceRegistry.Builder, Consumer<B>> defaultsRegistrar, NamedRegistrar<B> namedRegistrar,
            NamedRegistrar<B> instanceRegistrar, List<Attribute<B>> attributes) {
        this.name = name;
        this.builtIn = builtIn;
        this.toBuilder = toBuilder;
        this.build = build;
        this.defaultsRegistrar = defaultsRegistrar;
        this.namedRegistrar = namedRegistrar;
        this.instanceRegistrar = instanceRegistrar;
        for (Attribute<B> attribute : attributes) {
            this.attributes.put(attribute.name, attribute);
        }
    }

    @SuppressWarnings("java:S1452") // java:S1452: the policies have different builder and config types
    static @Nullable PolicyType<?, ?> find(String segment) {
        String normalized = Suggestions.normalize(segment);
        for (PolicyType<?, ?> policy : ALL) {
            if (Suggestions.normalize(policy.name).equals(normalized)) {
                return policy;
            }
        }
        return null;
    }

    static List<String> names() {
        return ALL.stream().map(p -> p.name).toList();
    }

    @Nullable Attribute<B> attribute(String segment) {
        String normalized = Suggestions.normalize(segment);
        for (Attribute<B> attribute : attributes.values()) {
            if (Suggestions.normalize(attribute.name).equals(normalized)) {
                return attribute;
            }
        }
        return null;
    }

    private Consumer<B> applier(Map<String, Setting> settings) {
        Map<String, Setting> copy = Map.copyOf(settings);
        return builder -> copy.values().forEach(s -> {
            Attribute<B> attribute = attributes.get(s.key().attribute());
            assert attribute != null;
            attribute.set(builder, s.value());
        });
    }

    void registerDefaults(ResilienceRegistry.Builder registry, Map<String, Setting> settings) {
        defaultsRegistrar.accept(registry, applier(settings));
    }

    /** Registers external settings of a named config, which override a programmatic config of the same name. */
    void registerNamed(ResilienceRegistry.Builder registry, String config, Map<String, Setting> settings) {
        namedRegistrar.register(registry, config, applier(settings));
    }

    void registerInstance(ResilienceRegistry.Builder registry, String instance, Map<String, Setting> settings) {
        instanceRegistrar.register(registry, instance, applier(settings));
    }

    /**
     * Resolves the defaults and every named configuration against the built-in defaults, as the
     * registry will, and reports invalid combinations with the keys that set them.
     */
    void validate(Map<String, Setting> defaults, Map<String, Map<String, Setting>> named, Problems problems) {
        C base;
        try {
            base = resolve(builtIn, defaults);
        } catch (IllegalArgumentException e) {
            problems.add(invalidConfig("defaults", e, defaults));
            return;
        }
        named.forEach((config, settings) -> {
            try {
                resolve(base, settings);
            } catch (IllegalArgumentException e) {
                problems.add(invalidConfig("config '" + config + "'", e, settings));
            }
        });
    }

    /**
     * Validates per-instance overrides. Their real base (the named config and annotation values of
     * the instance) is only known when the instance is created, so a value is reported here only if
     * it is invalid whatever the base: each setting is applied alone to the defaults, and all of them
     * together, and a failure counts only if every attribute that its message names is set by the
     * instance itself. Anything else is reported by the registry when the instance is created.
     */
    void validateInstances(Map<String, Setting> defaults, Map<String, Map<String, Setting>> instances,
            Problems problems) {
        C base;
        try {
            base = resolve(builtIn, defaults);
        } catch (IllegalArgumentException e) {
            return; // reported by validate
        }
        instances.forEach((instance, settings) -> {
            String what = "instance '" + instance + "'";
            boolean reported = false;
            for (Setting setting : settings.values()) {
                Map<String, Setting> alone = Map.of(Objects.requireNonNull(setting.key().attribute()), setting);
                reported |= reportIfIntrinsic(base, alone, what, problems);
            }
            if (!reported && settings.size() > 1) {
                reportIfIntrinsic(base, settings, what, problems);
            }
        });
    }

    private boolean reportIfIntrinsic(C base, Map<String, Setting> settings, String what, Problems problems) {
        try {
            resolve(base, settings);
            return false;
        } catch (IllegalArgumentException e) {
            List<String> named = mentioned(detail(e));
            if (named.isEmpty() || !settings.keySet().containsAll(named)) {
                return false;
            }
            problems.add(invalidConfig(what, e, settings));
            return true;
        }
    }

    /**
     * The message of a core config validation error without its {@code [R4C-REG-001] Invalid <policy> config: }
     * prefix: the problem is reported here under R4C-CFG-006, with the keys that set the values.
     */
    static String detail(IllegalArgumentException e) {
        return INVALID_VALUE_PREFIX.matcher(String.valueOf(e.getMessage())).replaceFirst("");
    }

    /** The attributes whose record component a validation message names. */
    private List<String> mentioned(String message) {
        return attributes.values().stream()
                .filter(a -> message.matches("(?s).*\\b" + a.field + "\\b.*"))
                .map(a -> a.name)
                .toList();
    }

    private C resolve(C start, Map<String, Setting> settings) {
        B builder = toBuilder.apply(start);
        applier(settings).accept(builder);
        return build.apply(builder);
    }

    private ProblemException invalidConfig(String what, IllegalArgumentException e, Map<String, Setting> settings) {
        String message = detail(e);
        List<Setting> involved = settings.values().stream()
                .filter(s -> {
                    Attribute<B> attribute = attributes.get(s.key().attribute());
                    return attribute != null && message.matches("(?s).*\\b" + attribute.field + "\\b.*");
                })
                .toList();
        if (involved.isEmpty()) {
            involved = List.copyOf(settings.values());
        }
        StringBuilder text = new StringBuilder("invalid ").append(name).append(' ').append(what).append(": ")
                .append(message).append(". Set by ");
        for (int i = 0; i < involved.size(); i++) {
            text.append(i == 0 ? "" : ", ").append(involved.get(i).origin());
        }
        return new ProblemException(ProblemException.INVALID_CONFIG, text.toString());
    }

    @Override
    public String toString() {
        return name;
    }
}
