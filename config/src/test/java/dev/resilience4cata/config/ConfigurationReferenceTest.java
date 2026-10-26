package dev.resilience4cata.config;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.BulkheadConfig;
import dev.resilience4cata.CircuitBreakerConfig;
import dev.resilience4cata.HedgeConfig;
import dev.resilience4cata.RateLimiterConfig;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.RetryConfig;
import dev.resilience4cata.TimeoutConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Keeps the configuration key reference ({@code docs/<language>/reference/configuration.md}) in step with
 * the code: every key of {@link PolicyType} is listed exactly once, with an environment variable that sets it,
 * the type of its {@link Attribute}, and a default that resolves to the built-in {@code *Config.DEFAULTS}.
 */
class ConfigurationReferenceTest {

    /** {@code | `key` | `ENV` | type | `default` or — | valid values | meaning |}. */
    private static final Pattern ROW = Pattern.compile(
            "^\\| `(defaults\\.[^`]+)` \\| `([A-Z0-9_]+)` \\| ([^|]+?) \\| (?:`([^`]*)`|(—)) \\|.*\\|$");
    private static final Map<String, Map<Attribute.Kind, String>> TYPES = Map.of(
            "en", Map.of(Attribute.Kind.BOOLEAN, "boolean", Attribute.Kind.INT, "int", Attribute.Kind.FLOAT, "decimal",
                    Attribute.Kind.DOUBLE, "decimal", Attribute.Kind.DURATION, "duration", Attribute.Kind.ENUM, "enum",
                    Attribute.Kind.CLASSES, "class list"),
            "es", Map.of(Attribute.Kind.BOOLEAN, "boolean", Attribute.Kind.INT, "int", Attribute.Kind.FLOAT, "decimal",
                    Attribute.Kind.DOUBLE, "decimal", Attribute.Kind.DURATION, "duración", Attribute.Kind.ENUM, "enum",
                    Attribute.Kind.CLASSES, "lista de clases"));
    private static final Map<String, Function<ResilienceRegistry, Object>> RESOLVED = Map.of(
            "retry", r -> r.retryConfig(""),
            "hedge", r -> r.hedgeConfig(""),
            "circuitBreaker", r -> r.circuitBreakerConfig(""),
            "rateLimiter", r -> r.rateLimiterConfig(""),
            "timeout", r -> r.timeoutConfig(""),
            "bulkhead", r -> r.bulkheadConfig(""));
    private static final Map<String, Object> BUILT_IN = Map.of(
            "retry", RetryConfig.DEFAULTS,
            "hedge", HedgeConfig.DEFAULTS,
            "circuitBreaker", CircuitBreakerConfig.DEFAULTS,
            "rateLimiter", RateLimiterConfig.DEFAULTS,
            "timeout", TimeoutConfig.DEFAULTS,
            "bulkhead", BulkheadConfig.DEFAULTS);

    private record Row(String key, String env, String type, String defaultValue) {
        String policy() {
            return key.split("\\.")[1];
        }

        String attribute() {
            return key.split("\\.")[2];
        }
    }

    private static List<Row> rows(String language) throws IOException {
        List<Row> rows = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of("..", "docs", language, "reference", "configuration.md"))) {
            Matcher row = ROW.matcher(line);
            if (row.matches()) {
                rows.add(new Row(row.group(1), row.group(2), row.group(3).strip(), row.group(4) == null ? "" : row.group(4)));
            }
        }
        return rows;
    }

    private static List<String> codeKeys() {
        List<String> keys = new ArrayList<>();
        for (PolicyType<?, ?> policy : PolicyType.ALL) {
            for (String attribute : policy.attributes.keySet()) {
                keys.add("defaults." + policy.name + "." + attribute);
            }
        }
        return keys;
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "es"})
    void everyKeyIsListedOnceInCodeOrder(String language) throws IOException {
        assertThat(rows(language)).extracting(Row::key).containsExactlyElementsOf(codeKeys());
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "es"})
    void everyTypeMatchesTheAttribute(String language) throws IOException {
        for (Row row : rows(language)) {
            PolicyType<?, ?> policy = PolicyType.find(row.policy());
            assertThat(policy).as(row.key()).isNotNull();
            Attribute<?> attribute = policy.attribute(row.attribute());
            assertThat(attribute).as(row.key()).isNotNull();
            assertThat(row.type()).as(row.key()).isEqualTo(TYPES.get(language).get(attribute.kind));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "es"})
    void everyDefaultIsTheBuiltInDefault(String language) throws IOException {
        for (Row row : rows(language)) {
            Properties properties = new Properties();
            properties.setProperty(row.key(), row.defaultValue());
            ResilienceRegistry registry = ResilienceConfig.builder().properties("reference", properties).build().toRegistry();
            assertThat(RESOLVED.get(row.policy()).apply(registry)).as(row.key() + " = " + row.defaultValue())
                    .isEqualTo(BUILT_IN.get(row.policy()));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "es"})
    void everyEnvironmentVariableSetsItsKey(String language) throws IOException {
        for (Row row : rows(language)) {
            String value = row.defaultValue().isEmpty() ? "java.lang.RuntimeException" : row.defaultValue();
            ResilienceConfig config = ResilienceConfig.builder().environment(Map.of(row.env(), value)).build();
            assertThat(config.describe()).as(row.env())
                    .contains(row.key() + " = " + value + "  <- environment variable '" + row.env() + "'");
        }
    }

    @Test
    void bothLanguagesListTheSameRows() throws IOException {
        Set<Row> english = new LinkedHashSet<>();
        for (Row row : rows("en")) {
            english.add(new Row(row.key(), row.env(), "", row.defaultValue()));
        }
        Set<Row> spanish = new LinkedHashSet<>();
        for (Row row : rows("es")) {
            spanish.add(new Row(row.key(), row.env(), "", row.defaultValue()));
        }
        assertThat(spanish).containsExactlyElementsOf(english);
    }
}
