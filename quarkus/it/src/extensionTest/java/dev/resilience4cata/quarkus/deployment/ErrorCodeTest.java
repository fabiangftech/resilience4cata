package dev.resilience4cata.quarkus.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Every Quarkus extension error code is unique, documented in both languages, and reported by a build: each code has
 * a test class here whose application fails with it (one application per class, as QuarkusExtensionTest requires).
 */
class ErrorCodeTest {

    private static final Pattern HEADING = Pattern.compile("(?m)^### (R4C-[A-Z]+-\\d{3})$");

    /** The test whose build reports each code. */
    static final Map<ErrorCode, Class<?>> TRIGGERS = Map.of(
            ErrorCode.FAULT_TOLERANCE_CLASH, FaultToleranceClashTest.class,
            ErrorCode.RETRY_INSIDE_TRANSACTION, RetryInsideTransactionTest.class,
            ErrorCode.SEVERAL_INTERFACES, SeveralInterfacesTest.class,
            ErrorCode.PRODUCER_ON_ANNOTATED_CLASS, ProducerOnAnnotatedClassTest.class,
            ErrorCode.UNPROXYABLE_ANNOTATED_CLASS, UnproxyableAnnotatedClassTest.class);

    @Test
    void codesAreUniqueAndWellFormed() {
        List<String> codes = Arrays.stream(ErrorCode.values()).map(ErrorCode::code).toList();
        assertThat(codes).doesNotHaveDuplicates().allMatch(c -> c.matches("R4C-QK-\\d{3}"));
    }

    @Test
    void everyCodeIsDocumentedInEnglishAndSpanish() throws IOException {
        for (String language : List.of("en", "es")) {
            String page = Files.readString(Path.of("..", "..", "docs", language, "errors.md"));
            Set<String> documented = HEADING.matcher(page).results().map(m -> m.group(1)).collect(Collectors.toSet());
            assertThat(documented).as(language)
                    .containsAll(Arrays.stream(ErrorCode.values()).map(ErrorCode::code).toList());
        }
    }

    @Test
    void everyCodeHasATriggeringBuild() throws IOException {
        assertThat(TRIGGERS).containsOnlyKeys(ErrorCode.values());
        for (Map.Entry<ErrorCode, Class<?>> trigger : TRIGGERS.entrySet()) {
            Path source = Path.of("src", "extensionTest", "java",
                    trigger.getValue().getName().replace('.', '/') + ".java");
            assertThat(Files.readString(source)).as(trigger.getValue().getSimpleName())
                    .contains("\"[" + trigger.getKey().code() + "]\"");
        }
    }
}
