package dev.resilience4cata.config;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.ResilienceRegistry;
import java.io.IOException;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

/**
 * Seeded fuzz test of {@code resilience4cata-config}, the module that reads input from outside the application.
 *
 * <p>{@link #validConfigurationsResolveAsTheModelSays}: each seed writes random, well-formed settings (defaults, named
 * configs, {@code extends}, profiles and {@code profiles.active}) into one to four sources (properties maps,
 * {@code Properties}, environment variables, system properties), with the key prefixes, kebab case and environment
 * variable forms the reference allows and the value forms each type accepts. A reference model written from
 * {@code docs/en/reference/configuration.md} computes what the registry must resolve: sources ranked by kind (or by
 * order), active profiles, a profile key winning within its source, {@code extends} chains. The core config builders
 * decide which combinations are valid. The contract: the registry's configs equal the model's exactly, or the build
 * fails as the model expects: one {@code R4C-CFG-007} per {@code extends} of a config that does not exist, otherwise
 * one {@code R4C-CFG-006} per invalid defaults or named config.
 *
 * <p>{@link #anyInputEndsInAConfigOrAConfigException}: the same settings, damaged (unknown or misspelt keys, values of
 * the wrong type or out of range, duplicates, {@code extends} cycles, odd instance ids, bad profile names). The
 * contract: the build returns a usable {@link ResilienceConfig} or throws a {@link ConfigException} whose every problem
 * has an {@code R4C-CFG} code. Never another exception.
 *
 * <p>Replay one seed with {@code ./gradlew :config:test --tests '*ConfigFuzzTest' -PfuzzSeed=<seed>}, run more with
 * {@code -PfuzzCount=<n>}, or other ones with {@code -PfuzzStart=<first seed>} (the nightly {@code fuzz} workflow
 * starts at a new seed every night).
 */
class ConfigFuzzTest {

    private static final long FIRST_SEED = Long.getLong("test.fuzz.start", 1);
    private static final int DEFAULT_COUNT = 1000;
    private static final int REPORTED = 5;

    private static final List<String> NAMES = List.of("alpha", "beta", "gamma");
    private static final List<String> PROFILES = List.of("dev", "prod");
    private static final List<Class<? extends Throwable>> THROWABLES = List.of(IOException.class,
            TimeoutException.class, IllegalStateException.class, RuntimeException.class, Exception.class);
    private static final long[] DURATIONS = {0, 1_000_000L, 10_000_000L, 100_000_000L, 250_000_000L, 1_000_000_000L,
        2_000_000_000L, 30_000_000_000L, 60_000_000_000L, 3_600_000_000_000L};
    private static final int[] INTEGERS = {0, 1, 2, 3, 5, 10, 20, 50, 100, 1000};
    private static final double[] DECIMALS = {0, 0.5, 1, 1.5, 2, 10, 50, 75.5, 100};
    private static final Pattern PROBLEM = Pattern.compile("(?s)^\\[R4C-CFG-0(0[1-9]|1[0-4])\\] .+");

    /** How a source is added to the builder, which also decides its rank. */
    private enum Kind {
        MAP(2), PROPERTIES(2), ENVIRONMENT(3), SYSTEM(4);

        final int priority;

        Kind(int priority) {
            this.priority = priority;
        }
    }

    /** One source: what the model knows (canonical key to typed value) and what is written (key text to value text). */
    private record Source(Kind kind, Map<String, Object> model, Map<String, String> written) {
    }

    /** A generated input and the model's view of it. */
    private record Input(long seed, List<Source> sources, boolean byOrder, List<String> builderProfiles) {

        ResilienceConfig.Builder builder() {
            ResilienceConfig.Builder builder = ResilienceConfig.builder();
            for (int i = 0; i < sources.size(); i++) {
                Source source = sources.get(i);
                switch (source.kind) {
                    case MAP -> builder.properties("source " + i, source.written, key -> null);
                    case PROPERTIES -> builder.properties("source " + i, properties(source.written));
                    case ENVIRONMENT -> builder.environment(source.written);
                    case SYSTEM -> builder.systemProperties(properties(source.written));
                }
            }
            if (byOrder) {
                builder.precedenceByOrder();
            }
            if (builderProfiles != null) {
                builder.profiles(builderProfiles.toArray(String[]::new));
            }
            return builder;
        }

        @Override
        public String toString() {
            StringBuilder out = new StringBuilder("seed ").append(seed).append(byOrder ? ", precedenceByOrder" : "")
                    .append(builderProfiles != null ? ", profiles(" + builderProfiles + ")" : "");
            for (int i = 0; i < sources.size(); i++) {
                out.append("\n  source ").append(i).append(" (").append(sources.get(i).kind).append(')');
                sources.get(i).written.forEach((k, v) -> out.append("\n    ").append(k).append(" = '").append(v).append('\''));
            }
            return out.toString();
        }
    }

    // ---- the two properties ----

    @Test
    void validConfigurationsResolveAsTheModelSays() {
        run(seed -> {
            Input input = new Generator(seed).valid();
            return checkAgainstModel(input);
        });
    }

    @Test
    void anyInputEndsInAConfigOrAConfigException() {
        run(seed -> {
            Generator generator = new Generator(seed);
            Input input = generator.damage(generator.valid());
            return checkContract(input);
        });
    }

    private static void run(Function<Long, String> check) {
        String one = System.getProperty("test.seed");
        int count = Integer.getInteger("test.fuzz.count", DEFAULT_COUNT);
        List<Long> seeds = one != null && !one.isBlank() ? List.of(Long.parseLong(one.trim()))
                : LongStream.range(FIRST_SEED, FIRST_SEED + count).boxed().toList();
        List<String> failures = new ArrayList<>();
        for (long seed : seeds) {
            String failure;
            try {
                failure = check.apply(seed);
            } catch (RuntimeException | AssertionError e) {
                failure = "seed " + seed + ": the check itself threw " + e;
            }
            if (failure != null) {
                failures.add(failure);
            }
        }
        assertThat(failures).as(() -> failures.size() + " of " + seeds.size() + " seeds failed; the first ones:\n\n"
                + String.join("\n\n", failures.subList(0, Math.min(REPORTED, failures.size())))
                + "\n\nReplay one with ./gradlew :config:test --tests '*ConfigFuzzTest' -PfuzzSeed=<seed>").isEmpty();
    }

    // ---- property 1: the model ----

    private static String checkAgainstModel(Input input) {
        Expected expected = Model.evaluate(input);
        ResilienceConfig config;
        try {
            config = input.builder().build();
        } catch (ConfigException e) {
            boolean allExpected = e.problems().stream().allMatch(p -> p.startsWith("[" + expected.code + "] "));
            if (expected.problems > 0 && allExpected && e.problems().size() == expected.problems) {
                return null;
            }
            return input + "\n  expected " + (expected.problems == 0 ? "success" : expected.problems
                    + " " + expected.code + " problems") + ", got: " + e.problems();
        } catch (RuntimeException e) {
            return input + "\n  unexpected " + e;
        }
        if (expected.problems > 0) {
            return input + "\n  expected " + expected.problems + " " + expected.code + " problems, but it built:\n"
                    + config.describe();
        }
        if (!config.activeProfiles().equals(expected.active)) {
            return input + "\n  active profiles " + config.activeProfiles() + ", expected " + expected.active;
        }
        if (!config.configNames().equals(expected.configs.keySet())) {
            return input + "\n  config names " + config.configNames() + ", expected " + expected.configs.keySet();
        }
        ResilienceRegistry registry = config.applyTo(ResilienceRegistry.builder()).build();
        for (PolicyType<?, ?> policy : PolicyType.ALL) {
            Object defaults = registryConfig(registry, policy, "default");
            if (!defaults.equals(expected.defaults.get(policy))) {
                return input + "\n  defaults of " + policy.name + ": " + defaults + "\n  expected " + expected.defaults.get(policy);
            }
            for (Map.Entry<String, Map<PolicyType<?, ?>, Object>> named : expected.configs.entrySet()) {
                Object want = named.getValue().get(policy);
                if (want == null) {
                    continue; // the config sets nothing for this policy
                }
                Object got = registryConfig(registry, policy, named.getKey());
                if (!got.equals(want)) {
                    return input + "\n  config " + named.getKey() + " of " + policy.name + ": " + got + "\n  expected " + want;
                }
            }
        }
        return null;
    }

    /** What the model expects: a count of problems with one code, or the resolved configs. */
    private record Expected(int problems, String code, List<String> active, Map<PolicyType<?, ?>, Object> defaults,
            Map<String, Map<PolicyType<?, ?>, Object>> configs) {
    }

    /** The reference model, from the rules in docs/en/reference/configuration.md. */
    private static final class Model {

        static Expected evaluate(Input input) {
            List<Source> ordered = new ArrayList<>(input.sources);
            if (!input.byOrder) {
                ordered.sort(Comparator.comparingInt(s -> s.kind.priority)); // stable: same kind, order added
            }
            List<String> active = activeProfiles(input, ordered);
            // Source first, then profile: within one source an active profile's key wins, in profile order.
            Map<String, Object> effective = new LinkedHashMap<>();
            for (Source source : ordered) {
                source.model.forEach((key, value) -> {
                    if (!key.startsWith("profiles.")) {
                        effective.put(key, value);
                    }
                });
                for (String profile : active) {
                    String prefix = "profiles." + profile + ".";
                    source.model.forEach((key, value) -> {
                        if (key.startsWith(prefix)) {
                            effective.put(key.substring(prefix.length()), value);
                        }
                    });
                }
            }
            Map<PolicyType<?, ?>, Map<String, Object>> defaults = new LinkedHashMap<>();
            Map<String, Map<PolicyType<?, ?>, Map<String, Object>>> own = new TreeMap<>();
            Map<String, String> parents = new TreeMap<>();
            effective.forEach((key, value) -> {
                String[] parts = key.split("\\.");
                if (parts[0].equals("defaults")) {
                    defaults.computeIfAbsent(policy(parts[1]), p -> new LinkedHashMap<>()).put(parts[2], value);
                } else if (parts.length == 3 && parts[2].equals("extends")) {
                    parents.put(parts[1], (String) value);
                } else {
                    own.computeIfAbsent(parts[1], n -> new LinkedHashMap<>())
                            .computeIfAbsent(policy(parts[2]), p -> new LinkedHashMap<>()).put(parts[3], value);
                }
            });
            Set<String> names = new java.util.TreeSet<>(own.keySet());
            names.addAll(parents.keySet());
            // R4C-CFG-007: one per extends whose parent is not a config; then nothing is validated
            long badExtends = parents.values().stream().filter(parent -> !names.contains(parent)).count();
            if (badExtends > 0) {
                return new Expected((int) badExtends, "R4C-CFG-007", active, Map.of(), Map.of());
            }

            int problems = 0;
            Map<PolicyType<?, ?>, Object> resolvedDefaults = new LinkedHashMap<>();
            Map<String, Map<PolicyType<?, ?>, Object>> resolvedConfigs = new TreeMap<>();
            names.forEach(name -> resolvedConfigs.put(name, new LinkedHashMap<>()));
            for (PolicyType<?, ?> policy : PolicyType.ALL) {
                Object base;
                try {
                    base = resolve(policy, builtIn(policy), defaults.getOrDefault(policy, Map.of()));
                } catch (IllegalArgumentException e) {
                    problems++;
                    continue;
                }
                resolvedDefaults.put(policy, base);
                for (String name : names) {
                    Map<String, Object> flat = new LinkedHashMap<>();
                    boolean any = false;
                    List<String> chain = new ArrayList<>();
                    for (String n = name; n != null; n = parents.get(n)) {
                        chain.add(n);
                    }
                    for (int i = chain.size() - 1; i >= 0; i--) {
                        Map<String, Object> settings = own.getOrDefault(chain.get(i), Map.of()).get(policy);
                        if (settings != null) {
                            flat.putAll(settings);
                            any = true;
                        }
                    }
                    if (!any) {
                        continue;
                    }
                    try {
                        resolvedConfigs.get(name).put(policy, resolve(policy, base, flat));
                    } catch (IllegalArgumentException e) {
                        problems++;
                    }
                }
            }
            return new Expected(problems, "R4C-CFG-006", active, resolvedDefaults, resolvedConfigs);
        }

        private static List<String> activeProfiles(Input input, List<Source> ordered) {
            if (input.builderProfiles != null) {
                return List.copyOf(new LinkedHashSet<>(input.builderProfiles));
            }
            List<String> active = List.of();
            for (Source source : ordered) {
                Object value = source.model.get("profiles.active");
                if (value != null) {
                    active = castNames(value);
                }
            }
            return List.copyOf(new LinkedHashSet<>(active));
        }

        @SuppressWarnings("unchecked")
        private static List<String> castNames(Object value) {
            return (List<String>) value;
        }
    }

    // ---- property 2: the contract on any input ----

    private static String checkContract(Input input) {
        ResilienceConfig config;
        try {
            config = input.builder().build();
        } catch (ConfigException e) {
            if (e.problems().isEmpty()) {
                return input + "\n  a ConfigException with no problem";
            }
            for (String problem : e.problems()) {
                if (!PROBLEM.matcher(problem).matches()) {
                    return input + "\n  a problem without an R4C-CFG code: " + problem;
                }
            }
            return null;
        } catch (RuntimeException | Error e) {
            return input + "\n  build() threw " + describe(e);
        }
        try {
            config.describe();
            ResilienceRegistry registry = config.applyTo(ResilienceRegistry.builder()).build();
            for (String name : config.configNames()) {
                for (PolicyType<?, ?> policy : PolicyType.ALL) {
                    try {
                        registryConfig(registry, policy, name);
                    } catch (IllegalArgumentException e) {
                        // a config that sets nothing for this policy is not registered for it
                        if (!String.valueOf(e.getMessage()).startsWith("[R4C-REG-")) {
                            return input + "\n  " + policy.name + "Config(" + name + ") threw " + describe(e);
                        }
                    }
                }
            }
            try {
                config.verifyInstances(registry);
            } catch (ConfigException e) {
                // R4C-CFG-013: overrides for instances that do not exist yet
            }
        } catch (RuntimeException | Error e) {
            return input + "\n  using the built config threw " + describe(e);
        }
        return null;
    }

    private static String describe(Throwable e) {
        StackTraceElement[] trace = e.getStackTrace();
        return e + (trace.length > 0 ? " at " + trace[0] : "");
    }

    // ---- the generator ----

    private static final class Generator {
        private final long seed;
        private final SplittableRandom random;

        Generator(long seed) {
            this.seed = seed;
            this.random = new SplittableRandom(seed);
        }

        Input valid() {
            int count = 1 + random.nextInt(4);
            List<Source> sources = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                sources.add(new Source(pick(Kind.values()), new LinkedHashMap<>(), new LinkedHashMap<>()));
            }
            Set<String> usedProfiles = new LinkedHashSet<>();
            int settings = random.nextInt(25);
            for (int i = 0; i < settings; i++) {
                Source source = pick(sources);
                String profile = random.nextInt(4) == 0 ? pick(PROFILES) : null;
                String prefix = profile == null ? "" : "profiles." + profile + ".";
                int what = random.nextInt(10);
                if (what < 1) {
                    // configs.<name>.extends: only to an earlier name, so there is never a cycle
                    int child = 1 + random.nextInt(NAMES.size() - 1);
                    String parent = NAMES.get(random.nextInt(child));
                    if (put(source, prefix + "configs." + NAMES.get(child) + ".extends", parent, parent)) {
                        noteProfile(usedProfiles, profile);
                    }
                    continue;
                }
                PolicyType<?, ?> policy = pick(PolicyType.ALL);
                Attribute<?> attribute = pick(List.copyOf(policy.attributes.values()));
                String section = what < 5 ? "defaults." : "configs." + pick(NAMES) + ".";
                Object value = value(attribute);
                if (put(source, prefix + section + policy.name + "." + attribute.name, value, text(attribute, value))) {
                    noteProfile(usedProfiles, profile);
                }
            }
            List<String> builderProfiles = null;
            List<String> active = new ArrayList<>(usedProfiles);
            if (!active.isEmpty() && random.nextBoolean()) {
                // only profiles that some key uses: an active profile with no key only logs a warning
                java.util.Collections.shuffle(active, new java.util.Random(random.nextLong()));
                active = active.subList(0, 1 + random.nextInt(active.size()));
                if (random.nextBoolean()) {
                    builderProfiles = active;
                } else {
                    put(pick(sources), "profiles.active", List.copyOf(active),
                            String.join(random.nextBoolean() ? "," : ", ", active));
                }
            }
            return new Input(seed, sources, random.nextInt(4) == 0, builderProfiles);
        }

        private static void noteProfile(Set<String> used, String profile) {
            if (profile != null) {
                used.add(profile);
            }
        }

        /** Adds a setting unless the source has that key already (a key set twice in one source is R4C-CFG-005). */
        private boolean put(Source source, String canonical, Object value, String text) {
            if (source.model.containsKey(canonical)) {
                return false;
            }
            source.model.put(canonical, value);
            source.written.put(write(source.kind, canonical), text);
            return true;
        }

        /** The key as written in a source of this kind: a prefix, and kebab case or environment variable form. */
        private String write(Kind kind, String canonical) {
            List<String> segments = new ArrayList<>(List.of(canonical.split("\\.")));
            if (kind == Kind.ENVIRONMENT) {
                StringBuilder out = new StringBuilder(random.nextBoolean() ? "R4C" : "RESILIENCE4CATA");
                for (String segment : segments) {
                    // MAXATTEMPTS or MAX_ATTEMPTS: words may be separated
                    String word = random.nextBoolean() ? segment : kebab(segment).replace('-', '_');
                    out.append('_').append(word.toUpperCase(Locale.ROOT));
                }
                return out.toString();
            }
            if (random.nextInt(3) == 0) {
                segments.replaceAll(ConfigFuzzTest::kebab);
            }
            String prefix = switch (kind) {
                case SYSTEM -> random.nextBoolean() ? "r4c." : "resilience4cata.";
                default -> pick(List.of("", "r4c.", "resilience4cata."));
            };
            return prefix + String.join(".", segments);
        }

        private Object value(Attribute<?> attribute) {
            return switch (attribute.kind) {
                case BOOLEAN -> random.nextBoolean();
                case INT -> INTEGERS[random.nextInt(INTEGERS.length)];
                case DOUBLE -> DECIMALS[random.nextInt(DECIMALS.length)];
                case DURATION -> Duration.ofNanos(DURATIONS[random.nextInt(DURATIONS.length)]);
                case ENUM -> pick(List.of(enumType(attribute).getEnumConstants()));
                case CLASSES -> {
                    List<Class<? extends Throwable>> pool = new ArrayList<>(THROWABLES);
                    java.util.Collections.shuffle(pool, new java.util.Random(random.nextLong()));
                    yield List.copyOf(pool.subList(0, 1 + random.nextInt(3)));
                }
            };
        }

        /** A text form of the value that the attribute's type accepts. */
        private String text(Attribute<?> attribute, Object value) {
            String text = switch (attribute.kind) {
                case BOOLEAN -> pick(List.of(value.toString(), value.toString().toUpperCase(Locale.ROOT),
                        Character.toUpperCase(value.toString().charAt(0)) + value.toString().substring(1)));
                case INT -> random.nextInt(5) == 0 ? "+" + value : value.toString();
                case DOUBLE -> decimal((Double) value);
                case DURATION -> duration((Duration) value);
                case ENUM -> {
                    String name = ((Enum<?>) value).name();
                    yield random.nextBoolean() ? name.toLowerCase(Locale.ROOT) : name;
                }
                case CLASSES -> {
                    List<String> names = new ArrayList<>();
                    for (Object type : (List<?>) value) {
                        names.add(((Class<?>) type).getName());
                    }
                    yield String.join(pick(List.of(",", ", ", " , ")), names);
                }
            };
            return random.nextInt(8) == 0 ? " " + text + " " : text;
        }

        private String decimal(double value) {
            if (value == Math.rint(value)) {
                long whole = (long) value;
                return pick(List.of(Long.toString(whole), whole + ".0", whole + "."));
            }
            return pick(List.of(Double.toString(value), Double.toString(value) + "0"));
        }

        private String duration(Duration value) {
            long nanos = value.toNanos();
            List<String> forms = new ArrayList<>(List.of(value.toString(), nanos + "ns"));
            if (nanos == 0) {
                forms.add("0");
            }
            long[] sizes = {1_000L, 1_000_000L, 1_000_000_000L, 60_000_000_000L, 3_600_000_000_000L};
            String[] units = {"us", "ms", "s", "m", "h"};
            for (int i = 0; i < sizes.length; i++) {
                if (nanos % sizes[i] == 0) {
                    forms.add(nanos / sizes[i] + units[i]);
                }
            }
            return pick(forms);
        }

        // ---- damage, for the contract property ----

        private static final List<String> BAD_VALUES = List.of("", "abc", "-1", "-0", "99999999999999999999", "1e400",
                "NaN", "Infinity", "-Infinity", "1.5.5", "10x", "PT-1S", "P", "PT", "99999999999999d", "9223372036854775807ns",
                "java.lang.String", "not.a.Class", ",", "java.io.IOException,,", "true1", "١٢", "0x10",
                "1_000", "  ", "\t", "null", "${x}", "[a]", "\"quoted\"", "été", "1ms,2ms", "-5s");

        /** Up to three damages (a key, a value, or an extra key), so that some inputs still build. */
        Input damage(Input input) {
            List<Map<String, String>> written = new ArrayList<>();
            input.sources.forEach(source -> written.add(new LinkedHashMap<>(source.written)));
            int damages = random.nextInt(4);
            for (int i = 0; i < damages; i++) {
                int at = random.nextInt(written.size());
                Map<String, String> entries = written.get(at);
                int what = random.nextInt(3);
                if (what < 2 && !entries.isEmpty()) {
                    String key = pick(List.copyOf(entries.keySet()));
                    String value = entries.remove(key);
                    if (what == 0) {
                        key = damageKey(key);
                    } else {
                        value = pick(BAD_VALUES);
                    }
                    entries.put(key, value);
                } else {
                    entries.put(junkKey(input.sources.get(at).kind),
                            random.nextBoolean() ? pick(BAD_VALUES) : pick(NAMES));
                }
            }
            List<Source> sources = new ArrayList<>();
            for (int i = 0; i < written.size(); i++) {
                sources.add(new Source(input.sources.get(i).kind, Map.of(), written.get(i)));
            }
            List<String> profiles = input.builderProfiles;
            if (random.nextInt(10) == 0) {
                profiles = List.of(pick(List.of("Dev!", "a b", "", " ", "dev", "x".repeat(300), "prod,dev")));
            }
            return new Input(input.seed, sources, input.byOrder, profiles);
        }

        private String damageKey(String key) {
            return switch (random.nextInt(9)) {
                case 0 -> key.substring(0, random.nextInt(key.length() + 1)); // cut short
                case 1 -> key + "." + key.substring(key.lastIndexOf('.') + 1); // a segment too many
                case 2 -> key.replaceFirst("[aeiou]", ""); // a typo
                case 3 -> key.toUpperCase(Locale.ROOT);
                case 4 -> "." + key;
                case 5 -> key + ".";
                case 6 -> key.replace(".", "..");
                case 7 -> key.replaceFirst("defaults|configs", "default");
                default -> key.replaceFirst("retry|timeout|bulkhead|hedge", "retries");
            };
        }

        private String junkKey(Kind kind) {
            String id = pick(List.of("payments", "a b", "com.acme.Api#get(java.lang.String)", "", "\"q\"", "x]y",
                    "UPPER", "with-hyphen", "é"));
            String key = switch (random.nextInt(8)) {
                case 0 -> "instances." + id + ".retry.maxAttempts";
                case 1 -> "instances.\"" + id + "\".timeout.value";
                case 2 -> "instances[" + id + "].bulkhead.maxConcurrent";
                case 3 -> "configs." + pick(NAMES) + ".extends";
                case 4 -> "configs." + id + ".retry.delay";
                case 5 -> "profiles." + id + ".defaults.retry.delay";
                case 6 -> "profiles.active";
                default -> pick(List.of("defaults", "defaults.retry", "configs", "debug", "token", "defualts.retry.delay"));
            };
            return switch (kind) {
                case ENVIRONMENT -> pick(List.of("R4C_", "RESILIENCE4CATA_"))
                        + key.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_");
                case SYSTEM -> pick(List.of("r4c.", "resilience4cata.")) + key;
                default -> pick(List.of("", "r4c.", "resilience4cata.")) + key;
            };
        }

        private <T> T pick(List<T> list) {
            return list.get(random.nextInt(list.size()));
        }

        private <T> T pick(T[] array) {
            return array[random.nextInt(array.length)];
        }
    }

    // ---- reflection on the config module's descriptors ----

    private static PolicyType<?, ?> policy(String name) {
        return PolicyType.ALL.stream().filter(p -> p.name.equals(name)).findFirst().orElseThrow();
    }

    private static Object builtIn(PolicyType<?, ?> policy) {
        return field(policy, "builtIn");
    }

    /** Applies typed values to a config, as the registry will; throws IllegalArgumentException if invalid. */
    @SuppressWarnings("unchecked")
    private static Object resolve(PolicyType<?, ?> policy, Object start, Map<String, Object> values) {
        Function<Object, Object> toBuilder = (Function<Object, Object>) field(policy, "toBuilder");
        Function<Object, Object> build = (Function<Object, Object>) field(policy, "build");
        Object builder = toBuilder.apply(start);
        values.forEach((name, value) -> {
            Attribute<?> attribute = policy.attributes.get(name);
            ((Attribute<Object>) attribute).set(builder, value);
        });
        return build.apply(builder);
    }

    private static Class<? extends Enum<?>> enumType(Attribute<?> attribute) {
        @SuppressWarnings("unchecked")
        Class<? extends Enum<?>> type = (Class<? extends Enum<?>>) field(attribute, "enumType");
        return type;
    }

    private static Object field(Object owner, String name) {
        try {
            Field field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(owner);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object registryConfig(ResilienceRegistry registry, PolicyType<?, ?> policy, String name) {
        return switch (policy.name) {
            case "retry" -> registry.retryConfig(name);
            case "hedge" -> registry.hedgeConfig(name);
            case "circuitBreaker" -> registry.circuitBreakerConfig(name);
            case "rateLimiter" -> registry.rateLimiterConfig(name);
            case "timeout" -> registry.timeoutConfig(name);
            case "bulkhead" -> registry.bulkheadConfig(name);
            case "adaptiveLimit" -> registry.adaptiveLimitConfig(name);
            default -> throw new AssertionError("a new policy: " + policy.name);
        };
    }

    private static Properties properties(Map<String, String> entries) {
        Properties properties = new Properties();
        properties.putAll(entries);
        return properties;
    }

    private static String kebab(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
    }
}
