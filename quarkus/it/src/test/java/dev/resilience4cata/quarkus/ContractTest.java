package dev.resilience4cata.quarkus;

import dev.resilience4cata.quarkus.runtime.ResilienceBuildTimeConfig;
import dev.resilience4cata.quarkus.runtime.ResilienceRuntimeConfig;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithName;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * The {@code quarkus.resilience4cata.*} property names and their phases (build time or run time), which
 * {@code docs/en/reference/compatibility.md} keeps within a major version, compared with {@code contract/quarkus.txt}
 * (see {@link Contract}). They are read from the extension's two {@code @ConfigMapping} roots as SmallRye Config names
 * them: each method in kebab case, nested groups as further segments. A plain test, not a {@code @QuarkusTest}.
 */
class ContractTest {

    @Test
    void propertyNamesAndPhases() throws Exception {
        Set<String> lines = new TreeSet<>();
        for (Class<?> root : new Class<?>[] {ResilienceBuildTimeConfig.class, ResilienceRuntimeConfig.class}) {
            String phase = root.getAnnotation(ConfigRoot.class).phase().name().toLowerCase(Locale.ROOT);
            collect(root.getAnnotation(ConfigMapping.class).prefix(), root, phase, lines);
        }
        Contract.check("quarkus.txt", lines);
    }

    private static void collect(String prefix, Class<?> group, String phase, Set<String> lines) {
        for (Method method : group.getDeclaredMethods()) {
            if (method.isSynthetic() || method.isDefault() || method.getParameterCount() > 0
                    || java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            WithName withName = method.getAnnotation(WithName.class);
            String name = prefix + "." + (withName != null ? withName.value() : kebab(method.getName()));
            Class<?> type = method.getReturnType();
            if (type.isInterface() && !type.getName().startsWith("java.")) {
                collect(name, type, phase, lines);
            } else {
                lines.add("property " + name + " " + phase);
                for (Object constant : type.isEnum() ? type.getEnumConstants() : new Object[0]) {
                    lines.add("property " + name + " value=" + kebab(((Enum<?>) constant).name().toLowerCase(Locale.ROOT)));
                }
            }
        }
    }

    private static String kebab(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
    }
}
