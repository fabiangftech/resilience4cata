package dev.resilience4cata.spring.autoconfigure;

import dev.resilience4cata.config.ResilienceConfig;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.origin.Origin;
import org.springframework.boot.origin.OriginLookup;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * Reads the {@code resilience4cata.*} keys of a Spring {@code Environment} into a {@link ResilienceConfig}.
 *
 * <p>Every property source is one source of the config, added lowest precedence first with
 * {@link ResilienceConfig.Builder#precedenceByOrder()}, so Spring's precedence holds (command line and test
 * properties over system properties over environment variables over {@code application.yml}). The
 * classpath resource {@code resilience4cata.properties}, if present, ranks below all of them. Spring's
 * names are mapped onto the config's key grammar: a map key in brackets
 * ({@code instances[com.acme.Api#get(java.lang.String)]}) becomes a quoted segment, and list elements
 * ({@code retry-on[0]}, {@code retry-on[1]}) one comma-separated value. Values are placeholder-resolved.
 * The environment variables source is read with the config's own {@code R4C_}/{@code RESILIENCE4CATA_}
 * grammar. Keys under {@code resilience4cata.spring} are the integration's switches and are skipped.
 */
final class SpringEnvironmentConfig {

    static final String PREFIX = "resilience4cata.";
    private static final String SPRING_PREFIX = "resilience4cata.spring";
    private static final List<String> SPRING_VARIABLES = List.of("R4C_SPRING_", "RESILIENCE4CATA_SPRING_");
    private static final List<String> VARIABLE_PREFIXES = List.of("R4C_", "RESILIENCE4CATA_");

    private SpringEnvironmentConfig() {
    }

    static ResilienceConfig read(ConfigurableEnvironment environment, @Nullable ClassLoader classLoader) {
        ResilienceConfig.Builder builder = ResilienceConfig.builder().precedenceByOrder()
                .optionalClasspath(ResilienceConfig.DEFAULT_RESOURCE);
        if (classLoader != null) {
            builder.classLoader(classLoader);
        }
        List<PropertySource<?>> sources = new ArrayList<>();
        environment.getPropertySources().forEach(sources::add);
        Collections.reverse(sources); // lowest precedence first
        for (PropertySource<?> source : sources) {
            if (ConfigurationPropertySources.isAttachedConfigurationPropertySource(source)) {
                continue; // a view of all the others
            }
            if (source instanceof SystemEnvironmentPropertySource variables) {
                Map<String, String> ours = environmentVariables(variables);
                if (!ours.isEmpty()) {
                    builder.environment(ours);
                }
            } else if (source instanceof EnumerablePropertySource<?> enumerable) {
                add(builder, enumerable, environment);
            }
        }
        return builder.build();
    }

    private static Map<String, String> environmentVariables(SystemEnvironmentPropertySource source) {
        Map<String, String> ours = new TreeMap<>();
        for (String name : source.getPropertyNames()) {
            if (VARIABLE_PREFIXES.stream().anyMatch(name::startsWith)
                    && SPRING_VARIABLES.stream().noneMatch(name::startsWith)) {
                Object value = source.getProperty(name);
                if (value != null) {
                    ours.put(name, value.toString());
                }
            }
        }
        return ours;
    }

    private static void add(ResilienceConfig.Builder builder, EnumerablePropertySource<?> source,
            ConfigurableEnvironment environment) {
        Map<String, String> values = new LinkedHashMap<>();
        Map<String, String> origins = new HashMap<>();
        Map<String, TreeMap<Integer, String>> lists = new LinkedHashMap<>();
        for (String name : source.getPropertyNames()) {
            if (!name.startsWith(PREFIX) || name.equals(SPRING_PREFIX) || name.startsWith(SPRING_PREFIX + ".")
                    || name.startsWith(SPRING_PREFIX + "[")) {
                continue;
            }
            Object raw = source.getProperty(name);
            if (raw == null) {
                continue;
            }
            String value = environment.resolveRequiredPlaceholders(raw.toString());
            Mapped mapped = map(name);
            origins.putIfAbsent(mapped.key(), origin(source, name));
            if (mapped.index() < 0) {
                values.put(mapped.key(), value);
            } else {
                lists.computeIfAbsent(mapped.key(), k -> new TreeMap<>()).put(mapped.index(), value);
            }
        }
        lists.forEach((key, elements) -> values.put(key, String.join(",", elements.values())));
        if (!values.isEmpty()) {
            builder.properties(source.getName(), values, origins::get);
        }
    }

    private static String origin(PropertySource<?> source, String name) {
        Origin origin = OriginLookup.getOrigin(source, name);
        return "'" + name + "' (" + (origin != null ? origin : source.getName()) + ")";
    }

    /**
     * A Spring property name in the config's grammar.
     *
     * @param key the key, with map keys as segments, quoted unless plain
     * @param index the list index, or {@code -1}
     */
    record Mapped(String key, int index) {
    }

    static Mapped map(String name) {
        StringBuilder key = new StringBuilder(name.length() + 4);
        int index = -1;
        int i = 0;
        while (i < name.length()) {
            char c = name.charAt(i);
            if (c != '[') {
                key.append(c);
                i++;
                continue;
            }
            int end = closing(name, i);
            if (end < 0) {
                key.append(name, i, name.length());
                break;
            }
            String inside = name.substring(i + 1, end);
            if (end == name.length() - 1 && !inside.isEmpty() && inside.chars().allMatch(Character::isDigit)) {
                index = Integer.parseInt(inside);
            } else {
                if (key.isEmpty() || key.charAt(key.length() - 1) != '.') {
                    key.append('.');
                }
                key.append(inside.matches("[A-Za-z0-9_-]+") ? inside : '"' + inside + '"');
            }
            i = end + 1;
        }
        return new Mapped(key.toString(), index);
    }

    /** The index of the {@code ]} that closes the {@code [} at {@code open}, counting nested brackets. */
    private static int closing(String name, int open) {
        int depth = 0;
        for (int i = open; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '[') {
                depth++;
            } else if (c == ']' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }
}
