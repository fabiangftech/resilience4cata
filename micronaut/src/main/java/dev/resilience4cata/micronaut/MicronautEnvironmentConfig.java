package dev.resilience4cata.micronaut;

import dev.resilience4cata.config.ResilienceConfig;
import io.micronaut.context.env.Environment;
import io.micronaut.context.env.EnvironmentPropertySource;
import io.micronaut.context.env.PropertySource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * Reads the {@code resilience4cata.*} keys of a Micronaut {@link Environment} into a {@link ResilienceConfig}.
 *
 * <p>Every property source is one source of the config, added lowest precedence first (Micronaut's
 * {@link PropertySource#getOrder() order}: {@code application.yml} &lt; {@code application-<env>.yml} &lt;
 * environment variables &lt; system properties &lt; command line) with
 * {@link ResilienceConfig.Builder#precedenceByOrder()}, so Micronaut's precedence holds. The classpath resource
 * {@code resilience4cata.properties}, if present, ranks below all of them. Keys are read as written (YAML maps are
 * flattened with dots by Micronaut, lists joined with commas); values are placeholder-resolved. Micronaut's
 * environment variable source has already turned {@code R4C_DEFAULTS_RETRY_MAX_ATTEMPTS} into several dotted
 * candidates, so the variables are read raw instead, with the config's own {@code R4C_}/{@code RESILIENCE4CATA_}
 * grammar, at that source's position. Keys under {@code resilience4cata.micronaut} are the integration's switches
 * and are skipped.
 */
final class MicronautEnvironmentConfig {

    private static final List<String> PREFIXES = List.of("resilience4cata.", "r4c.");
    private static final List<String> OWN = List.of("resilience4cata.micronaut", "r4c.micronaut");
    private static final List<String> VARIABLE_PREFIXES = List.of("R4C_", "RESILIENCE4CATA_");
    private static final List<String> OWN_VARIABLES = List.of("R4C_MICRONAUT_", "RESILIENCE4CATA_MICRONAUT_");

    private MicronautEnvironmentConfig() {
    }

    static ResilienceConfig read(Environment environment) {
        return read(environment, System.getenv(), environment.getClassLoader());
    }

    /**
     * @param variables the process environment, read at the position of Micronaut's environment source (if the
     *     environment has one)
     */
    static ResilienceConfig read(Environment environment, Map<String, String> variables,
            @Nullable ClassLoader classLoader) {
        ResilienceConfig.Builder builder = ResilienceConfig.builder().precedenceByOrder()
                .optionalClasspath(ResilienceConfig.DEFAULT_RESOURCE);
        if (classLoader != null) {
            builder.classLoader(classLoader);
        }
        for (PropertySource source : lowestFirst(environment.getPropertySources())) {
            if (EnvironmentPropertySource.NAME.equals(source.getName())) {
                Map<String, String> ours = environmentVariables(variables);
                if (!ours.isEmpty()) {
                    builder.environment(ours);
                }
            } else {
                add(builder, source, environment);
            }
        }
        return builder.build();
    }

    private static List<PropertySource> lowestFirst(Collection<PropertySource> sources) {
        List<PropertySource> sorted = new ArrayList<>(sources);
        sorted.sort(Comparator.comparingInt(PropertySource::getOrder)); // stable: equal orders keep Micronaut's order
        return sorted;
    }

    private static Map<String, String> environmentVariables(Map<String, String> variables) {
        Map<String, String> ours = new TreeMap<>();
        variables.forEach((name, value) -> {
            if (VARIABLE_PREFIXES.stream().anyMatch(name::startsWith) && OWN_VARIABLES.stream().noneMatch(name::startsWith)) {
                ours.put(name, value);
            }
        });
        return ours;
    }

    private static void add(ResilienceConfig.Builder builder, PropertySource source, Environment environment) {
        Map<String, String> values = new TreeMap<>();
        Map<String, String> origins = new HashMap<>();
        String location = source.getOrigin().location();
        for (String name : source) {
            if (PREFIXES.stream().noneMatch(name::startsWith) || isOwn(name)) {
                continue;
            }
            Object raw = source.get(name);
            if (raw == null || raw instanceof Map<?, ?>) {
                continue; // an empty YAML map, nothing to read
            }
            String value = raw instanceof Collection<?> list ? String.join(",", list.stream().map(String::valueOf).toList())
                    : String.valueOf(raw);
            values.put(name, environment.getPlaceholderResolver().resolveRequiredPlaceholders(value));
            origins.put(name, "'" + name + "' (" + location + ")");
        }
        if (!values.isEmpty()) {
            builder.properties(location, values, origins::get);
        }
    }

    private static boolean isOwn(String name) {
        for (String own : OWN) {
            if (name.equals(own) || name.startsWith(own + ".")) {
                return true;
            }
        }
        return false;
    }
}
