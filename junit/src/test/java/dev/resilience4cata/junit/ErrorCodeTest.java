package dev.resilience4cata.junit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectMethod;

import dev.resilience4cata.junit.fixtures.Fixtures;
import dev.resilience4cata.testkit.ManualClock;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.DiscoverySelector;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.testkit.engine.EngineTestKit;

/** Every JUnit extension error code is unique, documented in both languages, and reported by a fixture. */
class ErrorCodeTest {

    private static final Pattern HEADING = Pattern.compile("(?m)^### (R4C-[A-Z]+-\\d{3})$");
    private static final Pattern CODE = Pattern.compile("\\[(R4C-JU-\\d{3})] ");

    @Test
    void codesAreUniqueAndWellFormed() {
        List<String> codes = Arrays.stream(ErrorCode.values()).map(ErrorCode::code).toList();
        assertThat(codes).doesNotHaveDuplicates().allMatch(c -> c.matches("R4C-JU-\\d{3}"));
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
        reported.addAll(codes(selectClass(Fixtures.ConstructorInjection.class)));
        reported.addAll(codes(selectClass(Fixtures.WrongConfigureMethod.class)));
        reported.addAll(codes(selectMethod(Fixtures.PendingTasks.class, "leavesATask", ManualClock.class.getName())));

        assertThat(reported).containsExactlyInAnyOrderElementsOf(
                Arrays.stream(ErrorCode.values()).map(ErrorCode::code).toList());
    }

    /** The codes in the messages of every failure (and its causes) of a fixture run. */
    private static Set<String> codes(DiscoverySelector selector) {
        Set<String> codes = new TreeSet<>();
        EngineTestKit.engine("junit-jupiter").selectors(selector).execute().allEvents().failed().stream()
                .map(e -> e.getRequiredPayload(TestExecutionResult.class).getThrowable().orElseThrow())
                .forEach(failure -> {
                    for (Throwable t = failure; t != null; t = t.getCause()) {
                        Matcher m = CODE.matcher(String.valueOf(t.getMessage()));
                        while (m.find()) {
                            codes.add(m.group(1));
                        }
                    }
                });
        return codes;
    }
}
