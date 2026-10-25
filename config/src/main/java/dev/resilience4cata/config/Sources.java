package dev.resilience4cata.config;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/** The configuration sources and how their keys are read. */
final class Sources {

    /** Prefix of environment variables read. */
    static final String ENV_PREFIX = "R4C_";
    /** Long prefix of environment variables read, also accepted. */
    static final String ENV_LONG_PREFIX = "RESILIENCE4CATA_";
    /** Prefixes of system properties read; optional in files. */
    static final List<String> PREFIXES = List.of("resilience4cata.", "r4c.");

    private static final String CANNOT_READ = "cannot read ";

    /** Priorities: a source with a higher priority wins; equal priorities, the one added later wins. */
    static final int CLASSPATH_PRIORITY = 1;
    static final int FILE_PRIORITY = 2;
    static final int ENVIRONMENT_PRIORITY = 3;
    static final int SYSTEM_PROPERTIES_PRIORITY = 4;

    private Sources() {
    }

    /**
     * One key read from a source.
     *
     * @param key the dotted key without prefix
     * @param written the key as written, for messages
     * @param value the trimmed value
     * @param origin the key as written and where it comes from
     */
    record Raw(String key, String written, String value, String origin) {
    }

    /** A configuration source. */
    interface Source {
        int priority();

        /** Describes the source in messages. */
        String description();

        /** Reads the keys, adding a problem for each one that cannot be mapped to a key. */
        List<Raw> read(ClassLoader loader, Problems problems);
    }

    static Source text(String description, int priority, Function<ClassLoader, @Nullable String> text,
            boolean optional) {
        return new Source() {
            @Override
            public int priority() {
                return priority;
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public List<Raw> read(ClassLoader loader, Problems problems) {
                String content;
                try {
                    content = text.apply(loader);
                } catch (RuntimeException e) {
                    problems.add(new ProblemException(ProblemException.UNREADABLE, CANNOT_READ + description + ": "
                            + e.getMessage()));
                    return List.of();
                }
                if (content == null) {
                    if (!optional) {
                        problems.add(new ProblemException(ProblemException.UNREADABLE, CANNOT_READ + description
                                + ": not found"));
                    }
                    return List.of();
                }
                List<PropertiesReader.Entry> entries;
                try {
                    entries = PropertiesReader.read(content);
                } catch (IllegalArgumentException e) {
                    problems.add(new ProblemException(ProblemException.UNREADABLE, CANNOT_READ + description + ": "
                            + e.getMessage()));
                    return List.of();
                }
                List<Raw> raws = new ArrayList<>();
                for (PropertiesReader.Entry entry : entries) {
                    raws.add(new Raw(stripPrefix(entry.key()), entry.key(), entry.value().strip(),
                            "'" + entry.key() + "' (" + description + ", line " + entry.line() + ")"));
                }
                return raws;
            }
        };
    }

    static Source properties(String description, Properties properties) {
        Map<String, String> snapshot = new TreeMap<>();
        for (String name : properties.stringPropertyNames()) {
            snapshot.put(name, properties.getProperty(name));
        }
        return new Source() {
            @Override
            public int priority() {
                return FILE_PRIORITY;
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public List<Raw> read(ClassLoader loader, Problems problems) {
                List<Raw> raws = new ArrayList<>();
                snapshot.forEach((key, value) -> raws.add(new Raw(stripPrefix(key), key, value.strip(),
                        "'" + key + "' (" + description + ")")));
                return raws;
            }
        };
    }

    static Source systemProperties(Supplier<Properties> properties) {
        return new Source() {
            @Override
            public int priority() {
                return SYSTEM_PROPERTIES_PRIORITY;
            }

            @Override
            public String description() {
                return "system properties";
            }

            @Override
            public List<Raw> read(ClassLoader loader, Problems problems) {
                Properties props = properties.get();
                Map<String, String> sorted = new TreeMap<>();
                for (String name : props.stringPropertyNames()) {
                    for (String prefix : PREFIXES) {
                        if (name.startsWith(prefix)) {
                            sorted.put(name, props.getProperty(name));
                        }
                    }
                }
                List<Raw> raws = new ArrayList<>();
                sorted.forEach((key, value) -> raws.add(new Raw(stripPrefix(key), key, value.strip(),
                        "system property '" + key + "'")));
                return raws;
            }
        };
    }

    static Source environment(Supplier<Map<String, String>> environment) {
        return new Source() {
            @Override
            public int priority() {
                return ENVIRONMENT_PRIORITY;
            }

            @Override
            public String description() {
                return "environment variables";
            }

            @Override
            public List<Raw> read(ClassLoader loader, Problems problems) {
                Map<String, String> sorted = new TreeMap<>(environment.get());
                List<Raw> raws = new ArrayList<>();
                sorted.forEach((name, value) -> {
                    String rest;
                    if (name.startsWith(ENV_PREFIX)) {
                        rest = name.substring(ENV_PREFIX.length());
                    } else if (name.startsWith(ENV_LONG_PREFIX)) {
                        rest = name.substring(ENV_LONG_PREFIX.length());
                    } else {
                        return;
                    }
                    String origin = "environment variable '" + name + "'";
                    try {
                        raws.add(new Raw(EnvironmentNames.toKey(rest, origin), name, value.strip(), origin));
                    } catch (ProblemException e) {
                        problems.add(e);
                    }
                });
                return raws;
            }
        };
    }

    static String stripPrefix(String key) {
        for (String prefix : PREFIXES) {
            if (key.startsWith(prefix)) {
                return key.substring(prefix.length());
            }
        }
        return key;
    }

    static @Nullable String readFile(Path path) {
        if (!Files.exists(path)) {
            return null;
        }
        try {
            return decode(Files.readAllBytes(path));
        } catch (IOException e) {
            throw new IllegalStateException(e.toString(), e);
        }
    }

    static @Nullable String readResource(ClassLoader loader, String name) {
        URL url = loader.getResource(name.startsWith("/") ? name.substring(1) : name);
        if (url == null) {
            return null;
        }
        try (InputStream in = url.openStream()) {
            return decode(in.readAllBytes());
        } catch (IOException e) {
            throw new IllegalStateException(e.toString(), e);
        }
    }

    /** Decodes UTF-8, rejecting malformed input instead of replacing it silently. */
    private static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new IllegalStateException("not valid UTF-8", e);
        }
    }

    /**
     * Maps environment variable names (without prefix) to dotted keys. The mapping lowercases and
     * turns every non-alphanumeric character into '_', so it cannot be reversed by splitting: instead
     * the name is matched against the key grammar with the known policy and attribute names, and must
     * match exactly one key. Policies and attributes may be written with or without '_' between words
     * ({@code CIRCUITBREAKER} or {@code CIRCUIT_BREAKER}); a '_' inside a config, profile or instance
     * name stands for '-'. Instance ids are therefore limited to such names
     * ({@code R4C_INSTANCES_PAYMENTS_RETRY_MAXATTEMPTS}); derived ids need a file or system property.
     */
    static final class EnvironmentNames {

        private EnvironmentNames() {
        }

        static String toKey(String name, String origin) {
            String[] tokens = name.toLowerCase(Locale.ROOT).split("_", -1);
            Set<String> keys = new LinkedHashSet<>();
            List<String> nearMisses = new ArrayList<>();
            key(tokens, keys, nearMisses);
            if (keys.size() == 1) {
                return keys.iterator().next();
            }
            if (keys.size() > 1) {
                throw new ProblemException(ProblemException.AMBIGUOUS, origin + " is ambiguous: it matches "
                        + String.join(" and ", keys) + ". Set the key in a properties file or system property instead");
            }
            String hint = nearMisses.isEmpty() ? ""
                    : ". Did you mean '" + Suggestions.environmentName(nearMisses.get(0)) + "'?";
            if (tokens.length > 0 && tokens[0].equals("instances")) {
                throw new ProblemException(ProblemException.UNKNOWN_KEY, "unknown " + origin + hint + " Expected "
                        + ENV_PREFIX + "INSTANCES_<NAME>_<POLICY>_<ATTRIBUTE>. In environment variables the instance"
                        + " id can only be a name of lowercase letters and digits, with '_' standing for '-'"
                        + " (payments-api: " + ENV_PREFIX + "INSTANCES_PAYMENTS_API_RETRY_MAXATTEMPTS); set any other"
                        + " id, such as a derived id com.acme.Api#get(java.lang.String), in a file or a system property:"
                        + " r4c.instances.\"<id>\".<policy>.<attribute>");
            }
            throw new ProblemException(ProblemException.UNKNOWN_KEY, "unknown " + origin + hint
                    + " Expected " + ENV_PREFIX + "DEFAULTS_<POLICY>_<ATTRIBUTE>, " + ENV_PREFIX
                    + "CONFIGS_<NAME>_<POLICY>_<ATTRIBUTE>, " + ENV_PREFIX + "CONFIGS_<NAME>_EXTENDS, " + ENV_PREFIX
                    + "INSTANCES_<NAME>_<POLICY>_<ATTRIBUTE>, " + ENV_PREFIX + "PROFILES_<PROFILE>_… or " + ENV_PREFIX + "PROFILES_ACTIVE, for example "
                    + ENV_PREFIX + "CONFIGS_EXTERNAL_RETRY_MAXATTEMPTS");
        }

        private static void key(String[] t, Set<String> keys, List<String> nearMisses) {
            if (t.length >= 1 && t[0].equals("profiles")) {
                if (t.length == 2 && t[1].equals("active")) {
                    keys.add("profiles.active");
                }
                for (int j = 2; j < t.length; j++) {
                    String profile = name(t, 1, j);
                    if (profile == null || profile.equals("active")) {
                        continue;
                    }
                    Set<String> bodies = new LinkedHashSet<>();
                    List<String> misses = new ArrayList<>();
                    body(t, j, bodies, misses);
                    bodies.forEach(b -> keys.add("profiles." + profile + "." + b));
                    misses.forEach(b -> nearMisses.add("profiles." + profile + "." + b));
                }
                return;
            }
            body(t, 0, keys, nearMisses);
        }

        private static void body(String[] t, int i, Set<String> keys, List<String> nearMisses) {
            if (i >= t.length) {
                return;
            }
            if (t[i].equals("defaults")) {
                policyAttribute(t, i + 1, "defaults.", keys, nearMisses);
            } else if (t[i].equals("configs")) {
                configs(t, i, keys, nearMisses);
            } else if (t[i].equals("instances")) {
                for (int j = i + 2; j < t.length; j++) {
                    String instance = name(t, i + 1, j);
                    if (instance != null) {
                        policyAttribute(t, j, "instances." + instance + ".", keys, nearMisses);
                    }
                }
            }
        }

        private static void configs(String[] t, int i, Set<String> keys, List<String> nearMisses) {
            for (int j = i + 2; j <= t.length; j++) {
                String config = name(t, i + 1, j);
                if (config == null) {
                    continue;
                }
                if (j == t.length - 1 && t[j].equals("extends")) {
                    keys.add("configs." + config + ".extends");
                }
                policyAttribute(t, j, "configs." + config + ".", keys, nearMisses);
            }
        }

        private static void policyAttribute(String[] t, int i, String prefix, Set<String> keys, List<String> nearMisses) {
            for (int k = i + 1; k < t.length; k++) {
                PolicyType<?, ?> policy = PolicyType.find(String.join("", List.of(t).subList(i, k)));
                if (policy == null) {
                    continue;
                }
                String written = String.join("", List.of(t).subList(k, t.length));
                Attribute<?> attribute = written.isEmpty() ? null : policy.attribute(written);
                if (attribute != null) {
                    keys.add(prefix + policy.name + "." + attribute.name);
                } else {
                    String closest = Suggestions.closest(written, List.copyOf(policy.attributes.keySet()));
                    if (closest != null) {
                        nearMisses.add(prefix + policy.name + "." + closest);
                    }
                }
            }
        }

        /** Tokens {@code [from, to)} as a config or profile name, or null if they cannot form one. */
        private static @Nullable String name(String[] t, int from, int to) {
            if (from >= to || to > t.length) {
                return null;
            }
            String name = String.join("-", List.of(t).subList(from, to));
            return Key.NAME_PATTERN.matcher(name).matches() ? name : null;
        }
    }
}
