package dev.resilience4cata.micronaut;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import dev.resilience4cata.micronaut.broken.Quotes;
import dev.resilience4cata.micronaut.broken.TransactionalLedger;
import dev.resilience4cata.micronaut.fixtures.Catalog;
import io.micronaut.context.ApplicationContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Every Micronaut integration error code is unique, documented in both languages, and reported. */
class ErrorCodeTest {

    private static final Pattern HEADING = Pattern.compile("(?m)^### (R4C-[A-Z]+-\\d{3})$");
    private static final Pattern CODE = Pattern.compile("\\[(R4C-MN-\\d{3})] ");

    @Test
    void codesAreUniqueAndWellFormed() {
        List<String> codes = Arrays.stream(ErrorCode.values()).map(ErrorCode::code).toList();
        assertThat(codes).doesNotHaveDuplicates().allMatch(c -> c.matches("R4C-MN-\\d{3}"));
    }

    @Test
    void everyCodeIsDocumentedInEnglishAndSpanish() throws IOException {
        for (String language : List.of("en", "es")) {
            String page = Files.readString(Path.of("..", "docs", language, "errors.md"));
            Set<String> documented = HEADING.matcher(page).results().map(m -> m.group(1)).collect(Collectors.toSet());
            assertThat(documented).as(language)
                    .containsAll(Arrays.stream(ErrorCode.values()).map(ErrorCode::code).toList());
        }
    }

    @Test
    void everyCodeIsReported() {
        Set<String> reported = new TreeSet<>();
        // The @ResilientBean mistakes stop the startup: every @ResilientBean definition is checked then.
        reported.addAll(codes("R4C-MN-001", context -> { }));
        reported.addAll(codes("R4C-MN-002", context -> { }));
        reported.addAll(codes("R4C-MN-003", context -> { }));
        reported.addAll(codes("R4C-MN-004", context -> context.getBean(Catalog.class)));
        reported.addAll(codes("R4C-MN-005", context -> context.getBean(TransactionalLedger.class)));

        assertThat(reported).containsExactlyInAnyOrderElementsOf(
                Arrays.stream(ErrorCode.values()).map(ErrorCode::code).toList());
    }

    @Test
    void retryInsideTransactionCanBeAllowedAndNamesOnlyJoiningPropagations() {
        Set<String> messages = messages("R4C-MN-005", context -> context.getBean(TransactionalLedger.class));
        assertThat(String.join("\n", messages)).contains("@Retry on post (REQUIRED)").doesNotContain("read");
        try (ApplicationContext context = ApplicationContext.run(Map.of("test.broken", "R4C-MN-005",
                "resilience4cata.micronaut.allow-retry-inside-transaction", "true"))) {
            assertThat(context.getBean(TransactionalLedger.class)).isNotNull();
        }
    }

    @Test
    void excludedClassesAreNotChecked() {
        try (ApplicationContext context = ApplicationContext.run(Map.of("test.broken", "R4C-MN-004",
                "resilience4cata.micronaut.exclude", Catalog.class.getName()))) {
            assertThat(context.getBean(Catalog.class).getClass()).isEqualTo(Catalog.class);
        }
    }

    /** The codes in the messages of the failure and its causes, when {@code use} runs with the broken bean present. */
    static Set<String> codes(String broken, Consumer<ApplicationContext> use) {
        Set<String> codes = new TreeSet<>();
        for (String message : messages(broken, use)) {
            Matcher m = CODE.matcher(message);
            while (m.find()) {
                codes.add(m.group(1));
            }
        }
        return codes;
    }

    private static Set<String> messages(String broken, Consumer<ApplicationContext> use) {
        Set<String> messages = new TreeSet<>();
        Throwable failure = catchThrowable(() -> {
            try (ApplicationContext context = ApplicationContext.run(Map.of("test.broken", broken))) {
                use.accept(context);
            }
        });
        assertThat(failure).as(broken).isNotNull();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            messages.add(String.valueOf(t.getMessage()));
        }
        return messages;
    }
}
