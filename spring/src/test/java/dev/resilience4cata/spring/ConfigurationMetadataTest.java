package dev.resilience4cata.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The IDE metadata of the policy keys ({@code META-INF/additional-spring-configuration-metadata.json}, merged by
 * Spring Boot's configuration processor into {@code spring-configuration-metadata.json}) is derived from
 * {@code resilience4cata-config}'s own attribute table, {@code PolicyType}: every
 * {@code resilience4cata.defaults.<policy>.<attribute>} with its type, built-in default and enum values. The
 * table is package-private, so it is read reflectively; when an attribute is added this test fails and writes
 * the expected file to {@code build/expected-additional-spring-configuration-metadata.json}, to copy over.
 */
class ConfigurationMetadataTest {

    private static final Path COMMITTED = Path.of("src", "main", "resources", "META-INF",
            "additional-spring-configuration-metadata.json");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void theCommittedMetadataMatchesTheConfigModulesAttributes() throws Exception {
        JsonNode expected = JSON.readTree(JSON.writeValueAsString(expected())); // numbers as a parser reads them
        JsonNode committed = Files.exists(COMMITTED) ? JSON.readTree(COMMITTED.toFile()) : JSON.createObjectNode();
        if (!committed.equals(expected)) {
            Path out = Path.of("build", "expected-additional-spring-configuration-metadata.json");
            Files.writeString(out, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(expected) + "\n");
        }
        assertThat(committed).as("copy build/expected-additional-spring-configuration-metadata.json to " + COMMITTED)
                .isEqualTo(expected);
    }

    @Test
    void thePublishedMetadataHasThePolicyKeysAndTheSwitches() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/META-INF/spring-configuration-metadata.json")) {
            assertThat(in).isNotNull();
            JsonNode metadata = JSON.readTree(in);
            Set<String> names = new TreeSet<>();
            metadata.get("properties").forEach(p -> names.add(p.get("name").asString()));
            assertThat(names).contains("resilience4cata.defaults.retry.max-attempts",
                    "resilience4cata.defaults.circuit-breaker.wait-in-open", "resilience4cata.instances",
                    "resilience4cata.spring.wrap", "resilience4cata.spring.health.down-when-open");
        }
    }

    private static ObjectNode expected() throws ReflectiveOperationException {
        ObjectNode root = JSON.createObjectNode();
        ArrayNode groups = root.putArray("groups");
        ArrayNode properties = root.putArray("properties");
        ArrayNode hints = root.putArray("hints");
        groups.addObject().put("name", "resilience4cata")
                .put("description", "Policy configuration, read by resilience4cata-config. See"
                        + " https://resilience4cata.dev/#/en/reference/configuration");
        Class<?> policyType = Class.forName("dev.resilience4cata.config.PolicyType");
        for (Object policy : (List<?>) get(policyType, null, "ALL")) {
            String name = (String) get(policyType, policy, "name");
            Record builtIn = (Record) get(policyType, policy, "builtIn");
            String prefix = "resilience4cata.defaults." + kebab(name);
            groups.addObject().put("name", prefix).put("description", "Defaults of every " + words(name)
                    + ": each annotation and named config starts from them.");
            for (Object attribute : ((Map<?, ?>) get(policyType, policy, "attributes")).values()) {
                property(properties, hints, prefix, name, builtIn, attribute);
            }
        }
        properties.addObject().put("name", "resilience4cata.configs")
                .put("type", "java.util.Map<java.lang.String,java.lang.String>")
                .put("description", "Named configs: configs.<name>.<policy>.<attribute>, and configs.<name>.extends"
                        + " to inherit another config.");
        properties.addObject().put("name", "resilience4cata.instances")
                .put("type", "java.util.Map<java.lang.String,java.lang.String>")
                .put("description", "Per-instance overrides: instances.<id>.<policy>.<attribute>, applied over the"
                        + " annotation. Put an id other than letters, digits, _ and - in brackets:"
                        + " instances[com.acme.Api#get(java.lang.String)].timeout.value.");
        properties.addObject().put("name", "resilience4cata.profiles.active")
                .put("type", "java.util.List<java.lang.String>")
                .put("description", "resilience4cata profiles to apply (profiles.<name>.* keys), later ones winning.");
        return root;
    }

    private static void property(ArrayNode properties, ArrayNode hints, String prefix, String policy, Record builtIn,
            Object attribute) throws ReflectiveOperationException {
        Class<?> type = attribute.getClass();
        String name = (String) get(type, attribute, "name");
        String field = (String) get(type, attribute, "field");
        String kind = get(type, attribute, "kind").toString();
        Class<?> enumType = (Class<?>) get(type, attribute, "enumType");
        Object builtInValue = component(builtIn, field);
        String key = prefix + "." + kebab(name);
        ObjectNode property = properties.addObject().put("name", key);
        String what = switch (kind) {
            case "BOOLEAN" -> {
                property.put("type", "java.lang.Boolean").put("defaultValue", (Boolean) builtInValue);
                yield "true or false";
            }
            case "INT" -> {
                property.put("type", "java.lang.Integer").put("defaultValue", (Integer) builtInValue);
                yield "an integer";
            }
            case "FLOAT" -> {
                property.put("type", "java.lang.Float").put("defaultValue", (Float) builtInValue);
                yield "a number";
            }
            case "DOUBLE" -> {
                property.put("type", "java.lang.Double").put("defaultValue", (Double) builtInValue);
                yield "a number";
            }
            case "DURATION" -> {
                property.put("type", "java.time.Duration").put("defaultValue", duration((Duration) builtInValue));
                yield "a duration such as 250ms, 2s or PT2S";
            }
            case "ENUM" -> {
                property.put("type", "java.lang.String")
                        .put("defaultValue", ((Enum<?>) builtInValue).name().toLowerCase(Locale.ROOT));
                ArrayNode values = hints.addObject().put("name", key).putArray("values");
                for (Object constant : enumType.getEnumConstants()) {
                    values.addObject().put("value", ((Enum<?>) constant).name().toLowerCase(Locale.ROOT));
                }
                yield "one of the listed values";
            }
            case "CLASSES" -> {
                property.put("type", "java.util.List<java.lang.Class<? extends java.lang.Throwable>>");
                List<String> classes = new ArrayList<>();
                ((List<?>) builtInValue).forEach(c -> classes.add(((Class<?>) c).getName()));
                if (!classes.isEmpty()) {
                    ArrayNode defaults = property.putArray("defaultValue");
                    classes.forEach(defaults::add);
                }
                yield "exception class names";
            }
            default -> throw new AssertionError("a new attribute kind: " + kind);
        };
        property.put("description", "Default " + name + " of every " + words(policy) + " (" + what + ").");
    }

    private static Object get(Class<?> type, Object instance, String field) throws ReflectiveOperationException {
        Field f = type.getDeclaredField(field);
        f.setAccessible(true);
        return f.get(instance);
    }

    private static Object component(Record record, String name) throws ReflectiveOperationException {
        for (RecordComponent component : record.getClass().getRecordComponents()) {
            if (component.getName().equals(name)) {
                return component.getAccessor().invoke(record);
            }
        }
        throw new AssertionError(record.getClass() + " has no component " + name);
    }

    /** In the config grammar: the largest unit that divides the duration. */
    private static String duration(Duration duration) {
        long nanos = duration.toNanos();
        if (nanos == 0) {
            return "0";
        }
        String[] units = {"d", "h", "m", "s", "ms", "us", "ns"};
        long[] sizes = {86_400_000_000_000L, 3_600_000_000_000L, 60_000_000_000L, 1_000_000_000L, 1_000_000L, 1_000L, 1L};
        for (int i = 0; i < units.length; i++) {
            if (nanos % sizes[i] == 0) {
                return nanos / sizes[i] + units[i];
            }
        }
        throw new AssertionError(duration);
    }

    private static String kebab(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
    }

    private static String words(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT);
    }
}
