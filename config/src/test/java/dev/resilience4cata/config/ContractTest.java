package dev.resilience4cata.config;

import java.lang.reflect.Field;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * The configuration keys of every policy attribute, with their environment variables, value types and enum values,
 * compared with {@code contract/config-keys.txt} (see {@link Contract}): {@code docs/en/reference/compatibility.md}
 * promises to keep them within a major version. {@link ConfigurationReferenceTest} checks that each key and variable
 * really sets its attribute.
 */
class ContractTest {

    @Test
    void policyKeys() throws Exception {
        Set<String> lines = new TreeSet<>();
        Field enumType = Attribute.class.getDeclaredField("enumType");
        enumType.setAccessible(true);
        for (PolicyType<?, ?> policy : PolicyType.ALL) {
            for (Attribute<?> attribute : policy.attributes.values()) {
                String key = "defaults." + policy.name + "." + attribute.name;
                String variable = "R4C_" + key.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_");
                lines.add(key + " " + variable + " " + attribute.kind.name().toLowerCase(Locale.ROOT));
                if (enumType.get(attribute) instanceof Class<?> type) {
                    for (Object constant : type.getEnumConstants()) {
                        lines.add(key + " value=" + ((Enum<?>) constant).name().toLowerCase(Locale.ROOT));
                    }
                }
            }
        }
        Contract.check("config-keys.txt", lines);
    }
}
