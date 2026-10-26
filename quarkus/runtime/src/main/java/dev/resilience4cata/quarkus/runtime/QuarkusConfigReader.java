package dev.resilience4cata.quarkus.runtime;

import dev.resilience4cata.config.ResilienceConfig;
import io.smallrye.config.ConfigValue;
import io.smallrye.config.EnvConfigSource;
import io.smallrye.config.SmallRyeConfig;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.jspecify.annotations.Nullable;

/**
 * Reads the {@code resilience4cata.*} (and {@code r4c.*}) keys of the application's SmallRye Config into a
 * {@link ResilienceConfig}, keeping Quarkus's precedence and profiles.
 *
 * <p>Every key is read through {@link SmallRyeConfig#getConfigValue}, so the active profile ({@code %dev.},
 * {@code %test.}, {@code %prod.}) and expressions are already resolved, and each value knows its source and line,
 * which the config's error messages show ({@code 'resilience4cata.defaults.retry.delay'
 * (application.properties:3)}). Values whose winning source is below the environment variables (ordinal 300:
 * {@code application.properties}, YAML, {@code resilience4cata.properties} on the class path) rank lowest; then the
 * environment variables ({@code R4C_…}, {@code RESILIENCE4CATA_…}, including a {@code .env} file), read with the
 * config's own name grammar, since SmallRye cannot map those names back to camelCase keys; then the rest (system
 * properties, test overrides). Keys under {@code quarkus.} are the extension's own and never reach here.
 */
final class QuarkusConfigReader {

    private static final List<String> PREFIXES = List.of("resilience4cata.", "r4c.");
    private static final List<String> VARIABLES = List.of("R4C_", "RESILIENCE4CATA_");
    private static final int ENVIRONMENT_ORDINAL = 300;

    private QuarkusConfigReader() {
    }

    static ResilienceConfig read(SmallRyeConfig config, @Nullable ClassLoader classLoader) {
        ResilienceConfig.Builder builder = ResilienceConfig.builder().precedenceByOrder()
                .optionalClasspath(ResilienceConfig.DEFAULT_RESOURCE);
        if (classLoader != null) {
            builder.classLoader(classLoader);
        }
        List<String> environmentSources = new ArrayList<>();
        List<Map<String, String>> variables = new ArrayList<>();
        for (ConfigSource source : config.getConfigSources()) { // highest ordinal first
            if (source instanceof EnvConfigSource) {
                environmentSources.add(source.getName());
                Map<String, String> ours = new TreeMap<>();
                source.getProperties().forEach((name, value) -> {
                    if (value != null && VARIABLES.stream().anyMatch(name::startsWith)) {
                        ours.put(name, value);
                    }
                });
                variables.add(ours);
            }
        }
        Map<String, String> low = new TreeMap<>();
        Map<String, String> high = new TreeMap<>();
        Map<String, String> origins = new HashMap<>();
        for (String name : config.getPropertyNames()) {
            if (PREFIXES.stream().noneMatch(name::startsWith)) {
                continue; // also skips raw profile names such as %dev.resilience4cata.x
            }
            ConfigValue value = config.getConfigValue(name);
            if (value.getValue() == null || environmentSources.contains(value.getConfigSourceName())) {
                continue; // an environment variable won: read below with the config's own grammar
            }
            (value.getConfigSourceOrdinal() < ENVIRONMENT_ORDINAL ? low : high).put(name, value.getValue());
            origins.put(name, "'" + name + "' (" + location(value) + ")");
        }
        if (!low.isEmpty()) {
            builder.properties("application configuration", low, origins::get);
        }
        Collections.reverse(variables); // lowest ordinal first
        Map<String, String> environment = new TreeMap<>();
        variables.forEach(environment::putAll);
        if (!environment.isEmpty()) {
            builder.environment(environment);
        }
        if (!high.isEmpty()) {
            builder.properties("system properties", high, origins::get);
        }
        return builder.build();
    }

    /** {@code application.properties:12}, or the source's name when SmallRye knows no line. */
    private static String location(ConfigValue value) {
        String source = value.getConfigSourceName();
        String shortName = source == null ? "unknown source" : shortName(source);
        return value.getLineNumber() > 0 ? shortName + ":" + value.getLineNumber() : shortName;
    }

    /**
     * SmallRye names a file source {@code PropertiesConfigSource[source=file:/…/application.properties]}: keep the
     * file name, which is what users look for.
     */
    static String shortName(String source) {
        int start = source.indexOf("[source=");
        if (start < 0 || !source.endsWith("]")) {
            return source;
        }
        String location = source.substring(start + "[source=".length(), source.length() - 1);
        int slash = Math.max(location.lastIndexOf('/'), location.lastIndexOf('!'));
        return slash >= 0 && slash < location.length() - 1 ? location.substring(slash + 1) : location;
    }
}
