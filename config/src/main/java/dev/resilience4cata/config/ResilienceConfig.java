package dev.resilience4cata.config;

import dev.resilience4cata.AdaptiveLimitSnapshot;
import dev.resilience4cata.BulkheadSnapshot;
import dev.resilience4cata.CircuitBreakerSnapshot;
import dev.resilience4cata.HedgeSnapshot;
import dev.resilience4cata.RateLimiterSnapshot;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.ResilienceSnapshot;
import dev.resilience4cata.RetrySnapshot;
import dev.resilience4cata.TimeoutSnapshot;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * Configuration read from {@code .properties} files, classpath resources, environment variables
 * and system properties, validated and resolved once, then applied to a
 * {@link ResilienceRegistry.Builder}. Instances are immutable.
 *
 * <pre>{@code
 * ResilienceRegistry registry = ResilienceConfig.load()          // classpath resilience4cata.properties,
 *         .applyTo(ResilienceRegistry.builder())                  // environment, system properties
 *         .build();
 * }</pre>
 *
 * <h2>Keys</h2>
 * <pre>
 * defaults.retry.maxAttempts=3                          # defaults.&lt;policy&gt;.&lt;attribute&gt;
 * configs.external.circuitBreaker.failureRateThreshold=50  # configs.&lt;name&gt;.&lt;policy&gt;.&lt;attribute&gt;
 * configs.batch.extends=external                        # inherit every policy of another config
 * instances.payments.retry.maxAttempts=5               # instances.&lt;id&gt;.&lt;policy&gt;.&lt;attribute&gt;
 * instances."com.acme.Api#get(java.lang.String)".timeout.value=2s   # derived ids are quoted
 * profiles.test.configs.external.retry.maxAttempts=1    # applied only when the profile is active
 * profiles.active=test                                  # comma-separated, later profiles win
 * </pre>
 * Policies are {@code retry}, {@code hedge}, {@code circuitBreaker}, {@code rateLimiter}, {@code adaptiveLimit},
 * {@code timeout} and {@code bulkhead}; attributes are those of the annotation with the same name ({@code @Timeout}'s
 * duration is {@code timeout.value}). Policy and attribute names may be written in camelCase or
 * kebab-case ({@code circuit-breaker.wait-in-open}). Config and profile names are lowercase letters
 * and digits separated by single hyphens. An instance id is the policy's {@code name}, or the derived
 * {@code <interface>#<method>(<parameter types>)} when it has none; it is case-sensitive and must be
 * written in double quotes unless it is made of letters, digits, {@code _} and {@code -}. Keys may carry a {@code resilience4cata.} or {@code r4c.}
 * prefix; in system properties the prefix is required, and other system properties are ignored.
 *
 * <h2>Values</h2>
 * Durations use the annotations' grammar ({@code 250ms}, {@code 2s}, {@code PT2S}, {@code 0}); enums
 * are case-insensitive ({@code exponential}, {@code full}, {@code time}); exception lists are
 * comma-separated binary class names loaded with the configured class loader; booleans are
 * {@code true}/{@code false}.
 *
 * <h2>Environment variables</h2>
 * {@code R4C_} (or {@code RESILIENCE4CATA_}) followed by the key in uppercase with every
 * non-alphanumeric character replaced by {@code _}:
 * {@code R4C_CONFIGS_EXTERNAL_RETRY_MAXATTEMPTS} sets {@code configs.external.retry.maxAttempts},
 * {@code R4C_CONFIGS_SLOW_EXTERNAL_TIMEOUT_VALUE} sets {@code configs.slow-external.timeout.value}.
 * Policy and attribute words may also be separated ({@code CIRCUIT_BREAKER_WAIT_IN_OPEN}). A
 * variable must map to exactly one key. Instances can be addressed only by a name of lowercase
 * letters and digits ({@code R4C_INSTANCES_PAYMENTS_API_RETRY_MAXATTEMPTS} sets
 * {@code instances.payments-api.retry.maxAttempts}); other ids need a file or system property.
 *
 * <h2>Precedence</h2>
 * Lowest first: built-in defaults, {@code defaults.*}, {@code configs.<name>.*} (root of the
 * {@code extends} chain first; external values override a config of the same name registered in
 * code, attribute by attribute), then active profiles in order, then annotation attributes, then
 * {@code instances.<id>.*}, which override the annotations so that operators can retune an instance
 * without recompiling. For one
 * key, sources rank: classpath resources &lt; files and {@code Properties} &lt; environment
 * variables &lt; system properties; among sources of the same kind, the one added last wins. A
 * profile key wins over a non-profile key whatever their sources. {@link Builder#precedenceByOrder()}
 * ranks sources by the order they were added instead, for frameworks with their own precedence.
 *
 * <h2>Validation</h2>
 * Unknown keys, bad values, unknown exception classes, unknown {@code extends} targets, cycles and
 * invalid combinations (for example {@code maxDelay} shorter than {@code delay}) all fail
 * {@link Builder#build()} with one {@link ConfigException} that lists every problem, with its key and
 * source (file and line, environment variable or system property) and the closest valid key.
 * Nothing is reloaded afterwards. Instances are created later, when an annotated interface is wrapped,
 * so {@code build()} cannot know whether an instance id exists, and checks combinations of
 * per-instance values only when the instance sets every attribute involved; call
 * {@link #verifyInstances(ResilienceRegistry)} once the services are wrapped to catch ids that match
 * no instance.
 */
public final class ResilienceConfig {

    /** The classpath resource that {@link #load()} reads if present: {@value}. */
    public static final String DEFAULT_RESOURCE = "resilience4cata.properties";

    private static final String PROFILE = "profile";
    private static final System.Logger LOGGER = System.getLogger(ResilienceConfig.class.getName());

    private final List<String> activeProfiles;
    private final Map<PolicyType<?, ?>, Map<String, Setting>> defaults;
    private final Map<String, Map<PolicyType<?, ?>, Map<String, Setting>>> configs;
    private final Map<String, Map<PolicyType<?, ?>, Map<String, Setting>>> instances;

    private ResilienceConfig(List<String> activeProfiles, Map<PolicyType<?, ?>, Map<String, Setting>> defaults,
            Map<String, Map<PolicyType<?, ?>, Map<String, Setting>>> configs,
            Map<String, Map<PolicyType<?, ?>, Map<String, Setting>>> instances) {
        this.activeProfiles = List.copyOf(activeProfiles);
        this.defaults = immutable(defaults);
        this.configs = immutableByName(configs);
        this.instances = immutableByName(instances);
    }

    private static Map<String, Map<PolicyType<?, ?>, Map<String, Setting>>> immutableByName(
            Map<String, Map<PolicyType<?, ?>, Map<String, Setting>>> map) {
        Map<String, Map<PolicyType<?, ?>, Map<String, Setting>>> copy = new TreeMap<>();
        map.forEach((name, policies) -> copy.put(name, immutable(policies)));
        return Collections.unmodifiableMap(copy);
    }

    private static Map<PolicyType<?, ?>, Map<String, Setting>> immutable(Map<PolicyType<?, ?>, Map<String, Setting>> map) {
        Map<PolicyType<?, ?>, Map<String, Setting>> copy = new LinkedHashMap<>();
        for (PolicyType<?, ?> policy : PolicyType.ALL) {
            Map<String, Setting> settings = map.get(policy);
            if (settings != null && !settings.isEmpty()) {
                copy.put(policy, Collections.unmodifiableMap(new LinkedHashMap<>(settings)));
            }
        }
        return Collections.unmodifiableMap(copy);
    }

    /**
     * Loads the standard sources: the classpath resource {@value #DEFAULT_RESOURCE} if present, then
     * environment variables, then system properties, with the thread's context class loader.
     *
     * @return the validated configuration
     * @throws ConfigException if the configuration is invalid
     */
    public static ResilienceConfig load() {
        return builder().optionalClasspath(DEFAULT_RESOURCE).environment().systemProperties().build();
    }

    /**
     * Returns a builder with no sources.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Registers the configured defaults, named configs and per-instance overrides on a registry
     * builder. A named config is registered for each policy that it, or a config it extends, sets.
     *
     * <p>A named config may also be registered in code ({@code ResilienceRegistry.Builder.retry("name",
     * ...)}), before or after this call: the external values override the code's attribute by
     * attribute ({@code retryOverlay}). Per-instance overrides are registered with
     * {@code retryInstance(...)} and the like; programmatic calls to those made after this one win.
     * Programmatic {@code *Defaults(...)} calls made after this one replace the external defaults of
     * that policy.
     *
     * @param registry the builder to configure
     * @return {@code registry}, for chaining
     */
    public ResilienceRegistry.Builder applyTo(ResilienceRegistry.Builder registry) {
        Objects.requireNonNull(registry, "registry");
        defaults.forEach((policy, settings) -> policy.registerDefaults(registry, settings));
        configs.forEach((name, policies) -> policies.forEach((policy, settings) ->
                policy.registerNamed(registry, name, settings)));
        instances.forEach((id, policies) -> policies.forEach((policy, settings) ->
                policy.registerInstance(registry, id, settings)));
        return registry;
    }

    /**
     * Checks that every per-instance override ({@code instances.<id>.<policy>.*}) matches an instance
     * of that policy that {@code registry} has created. Instances are created when an annotated
     * interface is wrapped with {@code Resilient.of(...)} or a pipeline is built, so call this after
     * that, for example at the end of startup or in a test; an override that matches nothing is
     * otherwise silently unused.
     *
     * @param registry a registry built from this configuration
     * @throws ConfigException listing every override that matches no instance, with its source and
     *     the closest instance id
     */
    public void verifyInstances(ResilienceRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        ResilienceSnapshot snapshot = registry.snapshot();
        Map<PolicyType<?, ?>, Set<String>> known = new LinkedHashMap<>();
        known.put(PolicyType.RETRY, ids(snapshot.retries(), RetrySnapshot::instance));
        known.put(PolicyType.HEDGE, ids(snapshot.hedges(), HedgeSnapshot::instance));
        known.put(PolicyType.CIRCUIT_BREAKER, ids(snapshot.circuitBreakers(), CircuitBreakerSnapshot::instance));
        known.put(PolicyType.RATE_LIMITER, ids(snapshot.rateLimiters(), RateLimiterSnapshot::instance));
        known.put(PolicyType.TIMEOUT, ids(snapshot.timeouts(), TimeoutSnapshot::instance));
        known.put(PolicyType.ADAPTIVE_LIMIT, ids(snapshot.adaptiveLimits(), AdaptiveLimitSnapshot::instance));
        known.put(PolicyType.BULKHEAD, ids(snapshot.bulkheads(), BulkheadSnapshot::instance));
        Problems problems = new Problems();
        instances.forEach((id, policies) -> policies.forEach((policy, settings) -> {
            Set<String> ids = Objects.requireNonNull(known.get(policy));
            if (ids.contains(id)) {
                return;
            }
            String origins = settings.values().stream().map(Setting::origin).collect(Collectors.joining(", "));
            StringBuilder message = new StringBuilder(origins).append(settings.size() == 1 ? " sets " : " set ")
                    .append(policy.name).append(" instance '").append(id).append("', but this registry has no ")
                    .append(policy.name).append(" instance with that id");
            List<String> others = known.entrySet().stream().filter(e -> e.getValue().contains(id))
                    .map(e -> e.getKey().name).toList();
            if (!others.isEmpty()) {
                message.append(" ('").append(id).append("' is a ").append(String.join(" and ", others))
                        .append(" instance: per-instance overrides cannot add a policy that the method does not"
                                + " declare)");
            }
            String closest = Suggestions.closest(id, ids);
            if (closest != null) {
                message.append(". Did you mean '").append(Key.quoted(closest)).append("'?");
            } else {
                message.append(". An instance id is the policy's name, or <interface>#<method>(<parameter types>)"
                        + " when it has none");
            }
            message.append(" Known ").append(policy.name).append(" instances: ").append(ids);
            problems.add(new ProblemException(ProblemException.UNKNOWN_INSTANCE, message.toString()));
        }));
        problems.throwIfAny();
    }

    private static <S> Set<String> ids(List<S> snapshots, java.util.function.Function<S, String> id) {
        Set<String> ids = new TreeSet<>();
        snapshots.forEach(s -> ids.add(id.apply(s)));
        return ids;
    }

    /**
     * Builds a registry from this configuration alone: {@code applyTo(ResilienceRegistry.builder()).build()}.
     *
     * @return the registry
     */
    public ResilienceRegistry toRegistry() {
        return applyTo(ResilienceRegistry.builder()).build();
    }

    /**
     * Returns the active profiles, in the order they were applied.
     *
     * @return the profile names
     */
    public List<String> activeProfiles() {
        return activeProfiles;
    }

    /**
     * Returns the names of the configs defined externally, sorted.
     *
     * @return the config names
     */
    public Set<String> configNames() {
        return configs.keySet();
    }

    /**
     * Returns the instance ids that external configuration overrides ({@code instances.<id>.*}), sorted.
     *
     * @return the instance ids
     */
    public Set<String> instanceIds() {
        return instances.keySet();
    }

    /**
     * Describes every effective value and where it comes from, one per line, for diagnostics. Named
     * configs include what they inherit through {@code extends}.
     *
     * @return the description
     */
    public String describe() {
        StringBuilder out = new StringBuilder("active profiles: ").append(activeProfiles);
        defaults.forEach((policy, settings) -> settings.forEach((attribute, s) ->
                line(out, "defaults." + policy.name + "." + attribute, s)));
        configs.forEach((name, policies) -> policies.forEach((policy, settings) -> settings.forEach((attribute, s) ->
                line(out, "configs." + name + "." + policy.name + "." + attribute, s))));
        instances.forEach((id, policies) -> policies.forEach((policy, settings) -> settings.forEach((attribute, s) ->
                line(out, "instances." + Key.quoted(id) + "." + policy.name + "." + attribute, s))));
        return out.toString();
    }

    private static void line(StringBuilder out, String key, Setting setting) {
        out.append('\n').append(key).append(" = ").append(setting.text()).append("  <- ").append(setting.origin());
    }

    /** Returns {@link #describe()}. */
    @Override
    public String toString() {
        return describe();
    }

    /** Collects sources and options; {@link #build()} reads and validates them. Not thread-safe. */
    public static final class Builder {
        private final List<Sources.Source> sources = new ArrayList<>();
        private @Nullable ClassLoader classLoader;
        private @Nullable List<String> profiles;
        private boolean precedenceByOrder;

        private Builder() {
        }

        /**
         * Sets the class loader for exception classes and classpath resources. Defaults to the
         * thread's context class loader at {@link #build()} time, or this library's loader.
         *
         * @param classLoader the class loader
         * @return this builder
         */
        public Builder classLoader(ClassLoader classLoader) {
            this.classLoader = Objects.requireNonNull(classLoader, "classLoader");
            return this;
        }

        /**
         * Adds a classpath resource in {@code .properties} format (UTF-8), which must exist.
         *
         * @param resource the resource name, for example {@code "resilience4cata.properties"}
         * @return this builder
         */
        public Builder classpath(String resource) {
            return classpath(resource, false);
        }

        /**
         * Adds a classpath resource in {@code .properties} format (UTF-8), skipped if it does not exist.
         *
         * @param resource the resource name
         * @return this builder
         */
        public Builder optionalClasspath(String resource) {
            return classpath(resource, true);
        }

        private Builder classpath(String resource, boolean optional) {
            Objects.requireNonNull(resource, "resource");
            sources.add(Sources.text("classpath:" + resource, Sources.CLASSPATH_PRIORITY,
                    loader -> Sources.readResource(loader, resource), optional));
            return this;
        }

        /**
         * Adds a file in {@code .properties} format (UTF-8), which must exist.
         *
         * @param file the file
         * @return this builder
         */
        public Builder file(Path file) {
            return file(file, false);
        }

        /**
         * Adds a file in {@code .properties} format (UTF-8), skipped if it does not exist.
         *
         * @param file the file
         * @return this builder
         */
        public Builder optionalFile(Path file) {
            return file(file, true);
        }

        private Builder file(Path file, boolean optional) {
            Objects.requireNonNull(file, "file");
            Path absolute = file.toAbsolutePath();
            sources.add(Sources.text("file " + absolute, Sources.FILE_PRIORITY, loader -> Sources.readFile(absolute), optional));
            return this;
        }

        /**
         * Adds properties, which rank like a file. They are copied when this method is called.
         *
         * @param properties the properties
         * @return this builder
         */
        public Builder properties(Properties properties) {
            return properties("properties", properties);
        }

        /**
         * Adds properties, which rank like a file. They are copied when this method is called.
         *
         * @param description names the source in error messages
         * @param properties the properties
         * @return this builder
         */
        public Builder properties(String description, Properties properties) {
            Objects.requireNonNull(description, "description");
            Objects.requireNonNull(properties, "properties");
            sources.add(Sources.properties(description, properties));
            return this;
        }

        /**
         * Adds keys and values from a map, with where each key was set, for messages. They rank like a
         * file and are copied when this method is called. Meant for frameworks that keep their own
         * configuration sources, such as Spring's {@code Environment}, which knows the file and line of
         * every key.
         *
         * @param description names the source in error messages when {@code origins} knows nothing
         * @param properties keys (with or without the {@code resilience4cata.} or {@code r4c.} prefix)
         *     and values
         * @param origins where a key was set, used in error messages instead of
         *     {@code '<key>' (<description>)}, for example
         *     {@code 'resilience4cata.defaults.retry.delay' (class path resource [application.yml] - 3:14)};
         *     {@code null} for a key it does not know
         * @return this builder
         */
        public Builder properties(String description, Map<String, String> properties,
                Function<String, @Nullable String> origins) {
            Objects.requireNonNull(description, "description");
            Objects.requireNonNull(properties, "properties");
            Objects.requireNonNull(origins, "origins");
            Map<String, String> snapshot = new TreeMap<>();
            properties.forEach((key, value) -> snapshot.put(Objects.requireNonNull(key, "key"),
                    Objects.requireNonNull(value, "value")));
            Map<String, String> where = new HashMap<>();
            for (String key : snapshot.keySet()) {
                String origin = origins.apply(key);
                if (origin != null) {
                    where.put(key, origin);
                }
            }
            sources.add(Sources.map(description, snapshot, where));
            return this;
        }

        /**
         * Ranks sources by the order they were added, the last one added winning, instead of by kind
         * (classpath resources &lt; files and properties &lt; environment variables &lt; system
         * properties). For frameworks with their own precedence rules, such as Spring Boot, where
         * command-line arguments and test properties win over system properties: add the sources lowest
         * first. Profile keys still win over keys without a profile.
         *
         * @return this builder
         */
        public Builder precedenceByOrder() {
            this.precedenceByOrder = true;
            return this;
        }

        /** Adds properties text, for tests: ranks like a file. */
        Builder text(String description, String text) {
            sources.add(Sources.text(description, Sources.FILE_PRIORITY, loader -> text, false));
            return this;
        }

        /**
         * Adds the process environment ({@link System#getenv()}), read at {@link #build()} time.
         * Only variables starting with {@code R4C_} or {@code RESILIENCE4CATA_} are read.
         *
         * @return this builder
         */
        public Builder environment() {
            sources.add(Sources.environment(System::getenv));
            return this;
        }

        /**
         * Adds environment variables from a map, for example in tests.
         *
         * @param environment variable names and values; copied when this method is called
         * @return this builder
         */
        public Builder environment(Map<String, String> environment) {
            Map<String, String> copy = Map.copyOf(environment);
            sources.add(Sources.environment(() -> copy));
            return this;
        }

        /**
         * Adds the JVM system properties, read at {@link #build()} time. Only properties starting
         * with {@code resilience4cata.} or {@code r4c.} are read.
         *
         * @return this builder
         */
        public Builder systemProperties() {
            sources.add(Sources.systemProperties(System::getProperties));
            return this;
        }

        /**
         * Adds system properties from a {@code Properties} object, for example in tests.
         *
         * @param properties the properties; copied when this method is called
         * @return this builder
         */
        public Builder systemProperties(Properties properties) {
            Properties copy = new Properties();
            for (String name : properties.stringPropertyNames()) {
                copy.setProperty(name, properties.getProperty(name));
            }
            sources.add(Sources.systemProperties(() -> copy));
            return this;
        }

        /**
         * Sets the active profiles, overriding {@code profiles.active} from every source.
         *
         * @param profiles profile names, applied in this order (later ones win)
         * @return this builder
         */
        public Builder profiles(String... profiles) {
            this.profiles = List.of(profiles);
            return this;
        }

        /**
         * Reads every source, validates every key and value, and resolves profiles and
         * {@code extends} chains.
         *
         * @return the configuration
         * @throws ConfigException listing every problem found
         */
        public ResilienceConfig build() {
            ClassLoader loader = classLoader != null ? classLoader
                    : Objects.requireNonNullElseGet(Thread.currentThread().getContextClassLoader(),
                            ResilienceConfig.class::getClassLoader);
            Problems problems = new Problems();
            Map<String, Setting> merged = merge(loader, problems);
            List<String> active = activeProfiles(merged, problems);
            Map<String, Setting> effective = effective(merged, active);

            Map<PolicyType<?, ?>, Map<String, Setting>> defaults = new HashMap<>();
            Map<String, Map<PolicyType<?, ?>, Map<String, Setting>>> own = new HashMap<>();
            Map<String, Setting> parents = new HashMap<>();
            Map<String, Map<PolicyType<?, ?>, Map<String, Setting>>> instances = new HashMap<>();
            for (Setting s : effective.values()) {
                Key key = s.key();
                switch (key.section()) {
                    case DEFAULTS -> defaults.computeIfAbsent(Objects.requireNonNull(key.policy()),
                            p -> new LinkedHashMap<>()).put(Objects.requireNonNull(key.attribute()), s);
                    case CONFIG -> own.computeIfAbsent(Objects.requireNonNull(key.config()), c -> new HashMap<>())
                            .computeIfAbsent(Objects.requireNonNull(key.policy()), p -> new LinkedHashMap<>())
                            .put(Objects.requireNonNull(key.attribute()), s);
                    case EXTENDS -> parents.put(Objects.requireNonNull(key.config()), s);
                    case INSTANCE -> instances.computeIfAbsent(Objects.requireNonNull(key.instance()),
                                    c -> new HashMap<>())
                            .computeIfAbsent(Objects.requireNonNull(key.policy()), p -> new LinkedHashMap<>())
                            .put(Objects.requireNonNull(key.attribute()), s);
                    default -> {
                        // ACTIVE_PROFILES: never effective, already read by activeProfiles.
                    }
                }
            }

            Map<String, Map<PolicyType<?, ?>, Map<String, Setting>>> configs = flatten(own, parents, problems);
            if (problems.isEmpty()) {
                validate(defaults, configs, instances, problems);
            }
            problems.throwIfAny();
            return new ResilienceConfig(active, defaults, configs, instances);
        }

        /**
         * Reads every source, lowest priority first (or in the order added, with {@link #precedenceByOrder()}),
         * so that later sources override earlier ones.
         */
        private Map<String, Setting> merge(ClassLoader loader, Problems problems) {
            List<Sources.Source> ordered = new ArrayList<>(sources);
            if (!precedenceByOrder) {
                ordered.sort(Comparator.comparingInt(Sources.Source::priority));
            }
            Map<String, Setting> merged = new LinkedHashMap<>();
            for (Sources.Source source : ordered) {
                Map<String, Setting> own = new LinkedHashMap<>();
                for (Sources.Raw raw : source.read(loader, problems)) {
                    try {
                        read(source, raw, own, loader, problems);
                    } catch (ProblemException e) {
                        problems.add(e);
                    }
                }
                merged.putAll(own);
            }
            return merged;
        }

        private static void read(Sources.Source source, Sources.Raw raw, Map<String, Setting> own, ClassLoader loader,
                Problems problems) {
            Key key = Key.parse(raw.key(), raw.written(), raw.origin());
            Setting setting = new Setting(key, value(key, raw, loader), raw.value(), raw.origin());
            Setting previous = own.putIfAbsent(key.canonical(), setting);
            if (previous != null) {
                problems.add(new ProblemException(ProblemException.DUPLICATE_KEY, "'" + key.canonical()
                        + "' is set twice in " + source.description() + ": by " + previous.origin()
                        + " and by " + raw.origin()));
            }
        }

        /** The settings without a profile, overridden by those of each active profile in order. */
        private static Map<String, Setting> effective(Map<String, Setting> merged, List<String> active) {
            Map<String, Setting> effective = new LinkedHashMap<>();
            for (Setting s : merged.values()) {
                if (s.key().profile() == null && s.key().section() != Key.Section.ACTIVE_PROFILES) {
                    effective.put(s.key().canonical(), s);
                }
            }
            for (String profile : active) {
                boolean found = false;
                for (Setting s : merged.values()) {
                    if (profile.equals(s.key().profile())) {
                        found = true;
                        effective.put(s.key().withoutProfile().canonical(), s);
                    }
                }
                if (!found) {
                    LOGGER.log(System.Logger.Level.WARNING, "resilience4cata profile ''{0}'' is active but no key"
                            + " sets anything for it (profiles.{0}.*)", profile);
                }
            }
            return effective;
        }

        /** Each named config with the settings it inherits through {@code extends} merged in. */
        private static Map<String, Map<PolicyType<?, ?>, Map<String, Setting>>> flatten(
                Map<String, Map<PolicyType<?, ?>, Map<String, Setting>>> own, Map<String, Setting> parents,
                Problems problems) {
            Set<String> names = new TreeSet<>(own.keySet());
            names.addAll(parents.keySet());
            Map<String, Map<PolicyType<?, ?>, Map<String, Setting>>> configs = new TreeMap<>();
            Set<Set<String>> reportedCycles = new HashSet<>();
            for (String name : names) {
                List<String> chain = chain(name, names, parents, problems, reportedCycles);
                Map<PolicyType<?, ?>, Map<String, Setting>> flat = new HashMap<>();
                for (int i = chain.size() - 1; i >= 0; i--) {
                    own.getOrDefault(chain.get(i), Map.of()).forEach((policy, settings) ->
                            flat.computeIfAbsent(policy, p -> new LinkedHashMap<>()).putAll(settings));
                }
                configs.put(name, flat);
            }
            return configs;
        }

        /** Validates the defaults, named configs and instance overrides of every policy. */
        private static void validate(Map<PolicyType<?, ?>, Map<String, Setting>> defaults,
                Map<String, Map<PolicyType<?, ?>, Map<String, Setting>>> configs,
                Map<String, Map<PolicyType<?, ?>, Map<String, Setting>>> instances, Problems problems) {
            for (PolicyType<?, ?> policy : PolicyType.ALL) {
                Map<String, Setting> policyDefaults = defaults.getOrDefault(policy, Map.of());
                policy.validate(policyDefaults, byName(configs, policy), problems);
                policy.validateInstances(policyDefaults, byName(instances, policy), problems);
            }
        }

        /** The settings of one policy, by config name or instance id, sorted. */
        private static Map<String, Map<String, Setting>> byName(
                Map<String, Map<PolicyType<?, ?>, Map<String, Setting>>> settingsByName, PolicyType<?, ?> policy) {
            Map<String, Map<String, Setting>> result = new TreeMap<>();
            settingsByName.forEach((name, policies) -> {
                Map<String, Setting> settings = policies.get(policy);
                if (settings != null) {
                    result.put(name, settings);
                }
            });
            return result;
        }

        private List<String> activeProfiles(Map<String, Setting> merged, Problems problems) {
            List<String> active;
            if (profiles != null) {
                active = new ArrayList<>();
                for (String profile : profiles) {
                    try {
                        active.add(Key.name(Objects.requireNonNull(profile, PROFILE).strip(), PROFILE,
                                "ResilienceConfig.Builder.profiles(...)"));
                    } catch (ProblemException e) {
                        problems.add(e);
                    }
                }
            } else {
                Setting setting = merged.get("profiles.active");
                active = setting == null ? List.of() : castNames(setting.value());
            }
            return List.copyOf(new LinkedHashSet<>(active));
        }

        @SuppressWarnings("unchecked")
        private static List<String> castNames(Object value) {
            return (List<String>) value;
        }

        /** The configs that {@code name} inherits from, itself first; stops at an error. */
        private static List<String> chain(String name, Set<String> names, Map<String, Setting> parents,
                Problems problems, Set<Set<String>> reportedCycles) {
            List<String> chain = new ArrayList<>();
            String current = name;
            while (true) {
                if (chain.contains(current)) {
                    reportCycle(chain, current, parents, problems, reportedCycles);
                    return chain;
                }
                chain.add(current);
                Setting parent = parents.get(current);
                if (parent == null) {
                    return chain;
                }
                String target = (String) parent.value();
                if (!names.contains(target)) {
                    if (current.equals(name)) {
                        String closest = Suggestions.closest(target, names);
                        problems.add(new ProblemException(ProblemException.BAD_EXTENDS, parent.origin()
                                + " extends config '" + target + "', which is not defined in external configuration"
                                + (closest == null ? "." : ". Did you mean '" + closest + "'?") + " Known configs: "
                                + names));
                    }
                    return chain;
                }
                current = target;
            }
        }

        /** Reports the cycle that {@code current} closes in {@code chain}, once per set of configs. */
        private static void reportCycle(List<String> chain, String current, Map<String, Setting> parents,
                Problems problems, Set<Set<String>> reportedCycles) {
            List<String> cycle = new ArrayList<>(chain.subList(chain.indexOf(current), chain.size()));
            if (reportedCycles.add(new TreeSet<>(cycle))) {
                cycle.add(current);
                problems.add(new ProblemException(ProblemException.BAD_EXTENDS, "configs extend each other in"
                        + " a cycle: " + String.join(" -> ", cycle) + ", set by "
                        + cycle.subList(0, cycle.size() - 1).stream().map(c -> parents.get(c).origin())
                                .collect(Collectors.joining(", "))));
            }
        }

        private static Object value(Key key, Sources.Raw raw, ClassLoader loader) {
            return switch (key.section()) {
                case ACTIVE_PROFILES -> {
                    List<String> names = new ArrayList<>();
                    for (String part : raw.value().split(",", -1)) {
                        String name = part.strip();
                        if (!name.isEmpty() || raw.value().contains(",")) {
                            names.add(Key.name(name, PROFILE, raw.origin()));
                        }
                    }
                    yield List.copyOf(names);
                }
                case EXTENDS -> {
                    String target = Key.name(raw.value(), "config", raw.origin());
                    if (target.equals(key.config())) {
                        throw new ProblemException(ProblemException.BAD_EXTENDS, raw.origin()
                                + ": a config cannot extend itself");
                    }
                    yield target;
                }
                default -> {
                    PolicyType<?, ?> policy = Objects.requireNonNull(key.policy());
                    Attribute<?> attribute = Objects.requireNonNull(policy.attributes.get(key.attribute()));
                    yield attribute.parse(raw.value(), raw.origin(), loader);
                }
            };
        }
    }
}
