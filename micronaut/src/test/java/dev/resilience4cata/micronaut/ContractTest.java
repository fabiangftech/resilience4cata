package dev.resilience4cata.micronaut;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The {@code resilience4cata.micronaut.*} property names, which {@code docs/en/reference/compatibility.md} keeps within
 * a major version, compared with {@code contract/micronaut.txt} (see {@link Contract}). The integration reads them in
 * several classes, always as {@code MicronautSettings.PREFIX + ".<name>"} (in {@code @Property}, {@code @Requires} or
 * {@code getProperty}), so they are collected from the module's sources.
 */
class ContractTest {

    private static final Pattern KEY = Pattern.compile("PREFIX\\s*\\+\\s*\"((?:\\.[a-z0-9-]+)+)\"");

    @Test
    void propertyNames() throws Exception {
        Set<String> lines = new TreeSet<>();
        List<Path> sources;
        try (Stream<Path> files = Files.walk(Path.of("src", "main", "java"))) {
            sources = files.filter(p -> p.toString().endsWith(".java")).toList();
        }
        for (Path source : sources) {
            Matcher matcher = KEY.matcher(Files.readString(source));
            while (matcher.find()) {
                lines.add("property " + MicronautSettings.PREFIX + matcher.group(1));
            }
        }
        for (MicronautSettings.VerifyInstances value : MicronautSettings.VerifyInstances.values()) {
            lines.add("property " + MicronautSettings.PREFIX + ".verify-instances value="
                    + value.name().toLowerCase(Locale.ROOT));
        }
        Contract.check("micronaut.txt", lines);
    }
}
