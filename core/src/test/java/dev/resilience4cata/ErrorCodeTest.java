package dev.resilience4cata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.resilience4cata.fixture.Hidden;
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

/** Every runtime error code is unique, documented in both languages, and thrown by the call below. */
class ErrorCodeTest {

    private static final Pattern HEADING = Pattern.compile("(?m)^### (R4C-[A-Z]+-\\d{3})$");

    private final ResilienceRegistry registry = ResilienceRegistry.builder().build();

    @Test
    void codesAreUniqueAndWellFormed() {
        List<String> codes = Arrays.stream(ErrorCode.values()).map(ErrorCode::code).toList();
        assertThat(codes).doesNotHaveDuplicates().allMatch(c -> c.matches("R4C-(RT|REG)-\\d{3}"));
    }

    @Test
    void everyCodeIsDocumentedInEnglishAndSpanish() throws IOException {
        for (String language : List.of("en", "es")) {
            Set<String> documented = headings(Path.of("..", "docs", language, "errors.md"));
            assertThat(documented).as(language)
                    .containsAll(Arrays.stream(ErrorCode.values()).map(ErrorCode::code).toList());
        }
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void everyCodeIsThrown() {
        Map<ErrorCode, ThrowingCallable> triggers = new EnumMap<>(ErrorCode.class);
        triggers.put(ErrorCode.NOT_AN_INTERFACE, () -> Resilient.of(String.class, "x", registry));
        triggers.put(ErrorCode.NOT_AN_IMPLEMENTATION, () -> Resilient.of((Class) Runnable.class, "x", registry));
        triggers.put(ErrorCode.NOT_GENERATED, () -> Resilient.of(Runnable.class, () -> { }, registry));
        triggers.put(ErrorCode.INCOMPATIBLE_GENERATED, () -> Resilient.of(ErrorFixtures.Mismatched.class,
                new ErrorFixtures.Mismatched() { }, registry));
        triggers.put(ErrorCode.INACCESSIBLE_GENERATED, () -> Resilient.of(Hidden.class, new Hidden() { }, registry));
        triggers.put(ErrorCode.CONSTRUCTION_FAILED, () -> Resilient.of(ErrorFixtures.Failing.class,
                new ErrorFixtures.Failing() { }, registry));
        triggers.put(ErrorCode.CREATE_ON_INTERFACE, () -> Resilient.create(Runnable.class, registry));
        triggers.put(ErrorCode.NOT_EXTENSIBLE, () -> Resilient.create(ResilientTarget.Final.class, registry));
        triggers.put(ErrorCode.NO_MATCHING_CONSTRUCTOR, () -> Resilient.create(ResilientTarget.class, registry, 'c'));
        triggers.put(ErrorCode.AMBIGUOUS_CONSTRUCTOR, () -> Resilient.create(ResilientTarget.class, registry, (Object) null));
        triggers.put(ErrorCode.BLANK_PIPELINE_ID, () -> Pipeline.builder(registry, " "));
        triggers.put(ErrorCode.SET_TWICE, () -> Pipeline.builder(registry, "p").retry().retry());
        triggers.put(ErrorCode.FALLBACK_FILTER_WITHOUT_FALLBACK, () -> Pipeline.builder(registry, "p")
                .fallbackSkipOn(List.of(IOException.class)).build());
        triggers.put(ErrorCode.CLASSIFIER_WITHOUT_POLICY, () -> Pipeline.builder(registry, "p")
                .classifier((r, f) -> Classifier.Outcome.SUCCESS).build());
        triggers.put(ErrorCode.RETRY_CLASSIFIER_WITHOUT_RETRY, () -> Pipeline.builder(registry, "p")
                .circuitBreaker().retryClassifier((r, f) -> Classifier.Outcome.SUCCESS).build());
        triggers.put(ErrorCode.EXHAUSTED_RESULT_WITHOUT_CLASSIFIER, () -> Pipeline.builder(registry, "p").retry()
                .onExhaustedResult(Pipeline.ExhaustedResult.THROW).build());
        triggers.put(ErrorCode.CIRCUIT_BREAKER_CLASSIFIER_WITHOUT_CIRCUIT_BREAKER, () -> Pipeline.builder(registry, "p")
                .retry().circuitBreakerClassifier((r, f) -> Classifier.Outcome.SUCCESS).build());
        triggers.put(ErrorCode.INVALID_VALUE, () -> RetryConfig.builder().maxAttempts(0).build());
        triggers.put(ErrorCode.INVALID_NAMED_CONFIG, () -> ResilienceRegistry.builder()
                .retry("bad", r -> r.maxAttempts(0)).build());
        triggers.put(ErrorCode.INVALID_INSTANCE_CONFIG, () -> ResilienceRegistry.builder()
                .retryInstance("p", r -> r.maxAttempts(0)).build().retryPolicy("A#m()", "p", "", null));
        triggers.put(ErrorCode.UNKNOWN_CONFIG, () -> registry.retryConfig("fiev"));
        triggers.put(ErrorCode.DUPLICATE_CONFIG, () -> ResilienceRegistry.builder()
                .retry("five", r -> { }).retry("five", r -> { }));
        triggers.put(ErrorCode.RESERVED_CONFIG_NAME, () -> ResilienceRegistry.builder().retry("default", r -> { }));
        triggers.put(ErrorCode.EMPTY_INSTANCE_ID, () -> ResilienceRegistry.builder().retryInstance("", r -> { }));
        triggers.put(ErrorCode.CONFLICTING_INSTANCE, () -> {
            registry.retryPolicy("A#m()", "shared", "", null);
            registry.retryPolicy("B#n()", "shared", "", r -> r.maxAttempts(9));
        });
        triggers.put(ErrorCode.UNKNOWN_INSTANCE, () -> registry.bulkhead("nope"));

        assertThat(triggers.keySet()).containsExactlyInAnyOrder(ErrorCode.values());
        triggers.forEach((code, trigger) -> assertThatThrownBy(trigger).as(code.code())
                .isInstanceOf(RuntimeException.class).hasMessageStartingWith("[" + code.code() + "] "));
    }

    static Set<String> headings(Path file) throws IOException {
        Matcher matcher = HEADING.matcher(Files.readString(file));
        return matcher.results().map(m -> m.group(1)).collect(Collectors.toSet());
    }
}
