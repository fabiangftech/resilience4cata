package dev.resilience4cata.spring;

import dev.resilience4cata.spring.autoconfigure.ResilienceSpringProperties;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What {@code docs/en/reference/compatibility.md} keeps of the Spring Boot integration within a major version,
 * compared with {@code contract/spring.txt} (see {@link Contract}): the {@code resilience4cata.spring.*} property names
 * (from the metadata Spring Boot's configuration processor writes), the auto-configuration class names (for
 * {@code exclude}) and the bean names they define.
 */
class ContractTest {

    private static final String IMPORTS =
            "/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

    @Test
    void propertiesAutoConfigurationsAndBeans() throws Exception {
        Set<String> lines = new TreeSet<>();
        try (InputStream in = getClass().getResourceAsStream("/META-INF/spring-configuration-metadata.json")) {
            JsonNode metadata = JsonMapper.builder().build().readTree(in);
            for (JsonNode property : metadata.get("properties")) {
                String name = property.get("name").asString();
                if (name.startsWith("resilience4cata.spring.")) {
                    lines.add("property " + name);
                }
            }
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                getClass().getResourceAsStream(IMPORTS), StandardCharsets.UTF_8))) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                String name = line.strip();
                if (name.isEmpty() || name.startsWith("#")) {
                    continue;
                }
                lines.add("auto-configuration " + name);
                Deque<Class<?>> classes = new ArrayDeque<>(Set.of(Class.forName(name)));
                while (!classes.isEmpty()) {
                    Class<?> type = classes.pop();
                    for (Method method : type.getDeclaredMethods()) {
                        Bean bean = method.getAnnotation(Bean.class);
                        if (bean != null) {
                            String[] names = bean.name().length > 0 ? bean.name() : bean.value();
                            lines.add("bean " + (names.length > 0 ? names[0] : method.getName()));
                        }
                    }
                    classes.addAll(Set.of(type.getDeclaredClasses()));
                }
            }
        }
        for (ResilienceSpringProperties.VerifyInstances value : ResilienceSpringProperties.VerifyInstances.values()) {
            lines.add("property resilience4cata.spring.verify-instances value=" + value.name().toLowerCase(Locale.ROOT));
        }
        Contract.check("spring.txt", lines);
    }
}
