package dev.resilience4cata.config;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.ResilienceRegistry;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * A configuration file written for an earlier release keeps working: {@code docs/en/reference/compatibility.md}
 * promises it within a major version, and the contract file {@code config-keys.txt} only lists the keys, not what a
 * whole file of them does. {@code src/test/resources/upgrade/<release>.properties} uses every key of that release
 * (defaults of every attribute, named configs with {@code extends}, instances, a profile, both prefixes, kebab case).
 * The file of the last release ({@code r4c.apiBaseline}, which must have one) is read by that release itself, from
 * Maven Central in its own class loader, and must cover every attribute it knows; every file is read by the current
 * code without problems or warnings, to the same values as the release that wrote it.
 */
class UpgradeTest {

    private static final List<String> NAMES = List.of("", "slow", "slower");
    private static final List<String> POLICIES = List.of("retry", "hedge", "circuitBreaker", "rateLimiter",
            "adaptiveLimit", "timeout", "bulkhead");

    private static URLClassLoader baseline;

    @BeforeAll
    static void loadTheBaseline() throws IOException {
        String path = System.getProperty("test.baselineConfigPath");
        assertThat(path).as("set by the build (test.baselineConfigPath)").isNotBlank();
        List<URL> urls = new ArrayList<>();
        for (String jar : path.split(File.pathSeparator)) {
            urls.add(new File(jar).toURI().toURL());
        }
        baseline = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
    }

    @AfterAll
    static void close() throws IOException {
        baseline.close();
    }

    @Test
    void theLastReleaseHasAFileThatUsesEveryAttributeItKnows() throws Exception {
        String release = System.getProperty("test.apiBaseline");
        Path file = fixture(release + ".properties");
        assertThat(file).as("upgrade/%s.properties: add one for every new r4c.apiBaseline", release).exists();

        Set<String> keys = new TreeSet<>();
        for (String line : Files.readAllLines(file)) {
            if (line.startsWith("resilience4cata.defaults.")) {
                keys.add(line.substring("resilience4cata.".length(), line.indexOf('=')));
            }
        }
        assertThat(keys).containsAll(baselineAttributes());
    }

    @TestFactory
    Stream<DynamicTest> everyFileReadsLikeInTheReleaseThatWroteIt() throws Exception {
        String release = System.getProperty("test.apiBaseline");
        try (Stream<Path> files = Files.list(fixture(""))) {
            return files.sorted().toList().stream().map(file -> DynamicTest.dynamicTest(file.getFileName().toString(),
                    () -> {
                        Map<String, Map<String, String>> current = readByTheCurrentCode(file);
                        if (file.getFileName().toString().equals(release + ".properties")) {
                            Map<String, Map<String, String>> old = readByTheBaseline(file);
                            for (Map.Entry<String, Map<String, String>> config : old.entrySet()) {
                                assertThat(current.get(config.getKey()))
                                        .as("%s as the current code reads it (%s read it as %s)", config.getKey(),
                                                release, config.getValue())
                                        .containsAllEntriesOf(config.getValue());
                            }
                        }
                    }));
        }
    }

    /** Every policy config of the file's names, as the current code resolves them; fails on problems or warnings. */
    private static Map<String, Map<String, String>> readByTheCurrentCode(Path file) {
        List<String> warnings = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord logRecord) {
                if (logRecord.getLevel().intValue() >= Level.WARNING.intValue()) {
                    warnings.add(logRecord.getLoggerName() + ": " + logRecord.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger root = Logger.getLogger("");
        root.addHandler(handler);
        ResilienceRegistry registry;
        try {
            registry = ResilienceConfig.builder().file(file).build().toRegistry();
        } finally {
            root.removeHandler(handler);
        }
        assertThat(warnings).as("warnings while reading %s", file.getFileName()).isEmpty();
        Map<String, Map<String, String>> configs = new LinkedHashMap<>();
        for (String name : NAMES) {
            for (String policy : POLICIES) {
                configs.put(policy + " '" + name + "'", fields(String.valueOf(config(registry, policy, name))));
            }
        }
        return configs;
    }

    /** The same through the release's own classes; fails if that release rejects the file. */
    private static Map<String, Map<String, String>> readByTheBaseline(Path file) throws Exception {
        Class<?> type = Class.forName("dev.resilience4cata.config.ResilienceConfig", true, baseline);
        Object builder = type.getMethod("builder").invoke(null);
        builder.getClass().getMethod("file", Path.class).invoke(builder, file);
        Object config = builder.getClass().getMethod("build").invoke(builder);
        Object registry = type.getMethod("toRegistry").invoke(config);
        Map<String, Map<String, String>> configs = new LinkedHashMap<>();
        for (String name : NAMES) {
            for (String policy : POLICIES) {
                Method getter = registry.getClass().getMethod(policy + "Config", String.class);
                configs.put(policy + " '" + name + "'", fields(String.valueOf(getter.invoke(registry, name))));
            }
        }
        return configs;
    }

    private static Object config(ResilienceRegistry registry, String policy, String name) {
        return switch (policy) {
            case "retry" -> registry.retryConfig(name);
            case "hedge" -> registry.hedgeConfig(name);
            case "circuitBreaker" -> registry.circuitBreakerConfig(name);
            case "rateLimiter" -> registry.rateLimiterConfig(name);
            case "adaptiveLimit" -> registry.adaptiveLimitConfig(name);
            case "timeout" -> registry.timeoutConfig(name);
            default -> registry.bulkheadConfig(name);
        };
    }

    /** {@code defaults.<policy>.<attribute>} of every attribute the release's own {@code PolicyType} lists. */
    private static Set<String> baselineAttributes() throws Exception {
        Class<?> policyType = Class.forName("dev.resilience4cata.config.PolicyType", true, baseline);
        Field all = policyType.getDeclaredField("ALL");
        Field name = policyType.getDeclaredField("name");
        Field attributes = policyType.getDeclaredField("attributes");
        all.setAccessible(true);
        name.setAccessible(true);
        attributes.setAccessible(true);
        Set<String> keys = new TreeSet<>();
        for (Object policy : (List<?>) all.get(null)) {
            for (Object attribute : ((Map<?, ?>) attributes.get(policy)).keySet()) {
                keys.add("defaults." + name.get(policy) + "." + attribute);
            }
        }
        assertThat(keys).as("attributes of the release").hasSizeGreaterThan(50);
        return keys;
    }

    /** The {@code a=v} pairs of a config's {@code Name[a=v, b=[x, y]]} text, split outside brackets. */
    static Map<String, String> fields(String text) {
        Map<String, String> fields = new LinkedHashMap<>();
        String body = text.substring(text.indexOf('[') + 1, text.length() - 1);
        int depth = 0;
        int start = 0;
        for (int i = 0; i <= body.length(); i++) {
            char c = i < body.length() ? body.charAt(i) : ',';
            if (c == '[' || c == '(') {
                depth++;
            } else if (c == ']' || c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                String field = body.substring(start, i).strip();
                if (!field.isEmpty()) {
                    int equals = field.indexOf('=');
                    fields.put(field.substring(0, equals), field.substring(equals + 1));
                }
                start = i + 1;
            }
        }
        return fields;
    }

    private static Path fixture(String name) throws URISyntaxException {
        URL directory = UpgradeTest.class.getResource("/upgrade/");
        assertThat(directory).as("src/test/resources/upgrade").isNotNull();
        return Path.of(directory.toURI()).resolve(name);
    }

    @Test
    void fieldsAreSplitOutsideBrackets() {
        assertThat(fields("RetryConfig[enabled=true, retryOn=[class a.B, class c.D], delay=PT0.15S]"))
                .containsExactly(Map.entry("enabled", "true"), Map.entry("retryOn", "[class a.B, class c.D]"),
                        Map.entry("delay", "PT0.15S"));
        assertThat(Arrays.asList(fields("X[]").keySet().toArray())).isEmpty();
    }
}
