package dev.resilience4cata.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import dev.resilience4cata.Pipeline;
import dev.resilience4cata.ResilienceRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

/** Every testkit error code is unique, documented in both languages, and thrown by the call below. */
class ErrorCodeTest {

    private static final Pattern HEADING = Pattern.compile("(?m)^### (R4C-[A-Z]+-\\d{3})$");

    @Test
    void codesAreUniqueAndWellFormed() {
        List<String> codes = Arrays.stream(ErrorCode.values()).map(ErrorCode::code).toList();
        assertThat(codes).doesNotHaveDuplicates().allMatch(c -> c.matches("R4C-TK-\\d{3}"));
    }

    @Test
    void everyCodeIsDocumentedInEnglishAndSpanish() throws IOException {
        for (String language : List.of("en", "es")) {
            String page = Files.readString(Path.of("..", "docs", language, "errors.md"));
            Matcher headings = HEADING.matcher(page);
            Set<String> documented = headings.results().map(m -> m.group(1)).collect(Collectors.toSet());
            assertThat(documented).as(language)
                    .containsAll(Arrays.stream(ErrorCode.values()).map(ErrorCode::code).toList());
        }
    }

    @Test
    void everyCodeIsThrown() {
        ManualClock clock = new ManualClock();
        ResilienceRegistry registry = ResilienceTesting.registry(clock).build();
        Pipeline.builder(registry, "closed", String.class).circuitBreaker().build();

        Map<ErrorCode, ThrowingCallable> triggers = new EnumMap<>(ErrorCode.class);
        triggers.put(ErrorCode.CLOCK_BACKWARDS, () -> clock.advance(-1));
        triggers.put(ErrorCode.NEVER_IDLE, () -> {
            ManualClock busy = new ManualClock();
            busy.schedule(1, new Runnable() {
                @Override
                public void run() {
                    busy.schedule(1, this);
                }
            });
            busy.advanceUntilIdle();
        });
        triggers.put(ErrorCode.BREAKER_NOT_OPEN,
                () -> ResilienceTesting.elapseWaitInOpen(registry.circuitBreaker("closed"), clock));

        assertThat(triggers.keySet()).as("a trigger for every code").containsExactlyInAnyOrder(ErrorCode.values());
        triggers.forEach((code, call) -> assertThat(catchThrowable(call)).as(code.code())
                .hasMessageStartingWith("[" + code.code() + "] "));
    }
}
