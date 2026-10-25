package dev.resilience4cata.processor;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * javac's {@code -Xlint:processing} warns "No processor claimed any of these annotations" when processors ran
 * and some non-{@code java.lang} annotation was claimed by none of them. Users compiling with
 * {@code -Xlint:all -Werror} would fail on it, so the processor claims every annotation when it is the only
 * processor on the processor path, and claims nothing otherwise, so that other processors still see them.
 */
class ProcessingLintTest {

    private static final List<String> STRICT = List.of("-Xlint:all", "-Werror");

    private static final Map<String, String> SOURCES = Map.of("demo.Api", """
            package demo;

            import dev.resilience4cata.annotation.Retry;
            import org.jspecify.annotations.Nullable;

            public interface Api {
                @Retry
                @Nullable String find(String id);
            }
            """);

    @TempDir
    Path dir;

    @Test
    void aloneOnTheProcessorPathItClaimsEveryAnnotationSoStrictLintPasses() {
        Compilation result = Compilation.compile(dir, SOURCES, List.of(new ResilienceProcessor(true)), STRICT);

        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.success()).isTrue();
        assertThat(result.generated().resolve("demo/Api$Resilient.java")).exists();
    }

    @Test
    void claimingNothingIsWhatMakesStrictLintFail() {
        Compilation result = Compilation.compile(dir, SOURCES, List.of(new ResilienceProcessor(false)), STRICT);

        assertThat(result.success()).isFalse();
        assertThat(result.diagnostics()).map(d -> d.getMessage(java.util.Locale.ROOT))
                .anySatisfy(message -> assertThat(message).contains("No processor claimed any of these annotations")
                        .contains("dev.resilience4cata.annotation.Retry"))
                .anySatisfy(message -> assertThat(message).contains("-Werror"));
    }

    @Test
    void withOtherProcessorsItClaimsNothingSoTheyStillSeeEveryAnnotation() {
        Recording later = new Recording();

        Compilation result = Compilation.compile(dir, SOURCES,
                List.of(new ResilienceProcessor(false), later), List.of("-Xlint:all", "-Xlint:-processing", "-Werror"));

        assertThat(result.success()).isTrue();
        assertThat(later.seen).contains("dev.resilience4cata.annotation.Retry");
        assertThat(result.generated().resolve("demo/Api$Resilient.java")).exists();
    }

    @Test
    void claimingStopsLaterProcessorsFromSeeingAnnotationsWhichIsWhyItOnlyHappensWhenAlone() {
        Recording later = new Recording();

        Compilation.compile(dir, SOURCES, List.of(new ResilienceProcessor(true), later),
                List.of("-Xlint:all", "-Xlint:-processing", "-Werror"));

        assertThat(later.seen).isEmpty();
    }

    @Test
    void withOtherProcessorsTheClaimerKeepsStrictLintPassingAndTheOthersStillSeeTheAnnotations() {
        Recording before = new Recording();
        Recording after = new Recording();

        Compilation result = Compilation.compile(dir, SOURCES,
                List.of(before, new ResilienceProcessor(false), new AnnotationClaimer(), after), STRICT);

        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.success()).isTrue();
        assertThat(result.generated().resolve("demo/Api$Resilient.java")).exists();
        assertThat(before.seen).contains("dev.resilience4cata.annotation.Retry");
        assertThat(after.seen).as("only the library's own annotations are claimed")
                .doesNotContain("dev.resilience4cata.annotation.Retry");
    }

    @Test
    void theClaimerIsRegisteredRightAfterTheProcessorBecauseAClaimBeforeItWouldSkipIt() throws Exception {
        try (var in = ResilienceProcessor.class.getClassLoader()
                .getResourceAsStream("META-INF/services/javax.annotation.processing.Processor")) {
            List<String> lines = new String(java.util.Objects.requireNonNull(in).readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8).lines().filter(l -> !l.isBlank()).toList();
            assertThat(lines).containsExactly(ResilienceProcessor.class.getName(), AnnotationClaimer.class.getName());
        }
        // Why the order matters: a round with only the library's annotations ends once they are claimed.
        Compilation wrongOrder = Compilation.compile(dir, SOURCES,
                List.of(new AnnotationClaimer(), new ResilienceProcessor(false)), STRICT);
        assertThat(wrongOrder.generated().resolve("demo/Api$Resilient.java")).doesNotExist();
    }

    @Test
    void itIsAloneOnlyWhenEveryRegisteredProcessorIsItself() {
        String self = ResilienceProcessor.class.getName();

        assertThat(ResilienceProcessor.aloneAmong(List.of(self))).isTrue();
        assertThat(ResilienceProcessor.aloneAmong(List.of(self, self))).as("duplicate jars").isTrue();
        assertThat(ResilienceProcessor.aloneAmong(List.of(self, AnnotationClaimer.class.getName()))).isTrue();
        assertThat(ResilienceProcessor.aloneAmong(List.of(AnnotationClaimer.class.getName()))).isFalse();
        assertThat(ResilienceProcessor.aloneAmong(List.of(self, "org.mapstruct.ap.MappingProcessor"))).isFalse();
        assertThat(ResilienceProcessor.aloneAmong(List.of())).as("loaded without a service file").isFalse();
    }

    @Test
    void theRealServiceFileMakesItAloneOnThisTestPath() {
        assertThat(new ResilienceProcessor().claimsAnnotations()).isTrue();
    }

    /** A processor that runs after ours and records which annotations it was given. */
    private static final class Recording extends AbstractProcessor {
        final Set<String> seen = new TreeSet<>();

        @Override
        public Set<String> getSupportedAnnotationTypes() {
            return Set.of("*");
        }

        @Override
        public SourceVersion getSupportedSourceVersion() {
            return SourceVersion.latestSupported();
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
            annotations.forEach(a -> seen.add(a.getQualifiedName().toString()));
            return false;
        }
    }
}
