package dev.resilience4cata.processor;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code @PolicyOrder}: which layer the generated method starts with, and which one each layer calls. */
class PolicyOrderProcessorTest {

    @TempDir
    Path workDir;

    private Compilation compile(String name, String source) {
        return Compilation.compile(workDir.resolve(name), Map.of("demo.Api", source));
    }

    private static String api(String annotations) {
        return """
                package demo;
                import dev.resilience4cata.annotation.*;
                import static dev.resilience4cata.annotation.Layer.*;
                public interface Api {
                    %s
                    String call(String id);
                }
                """.formatted(annotations);
    }

    /** The generated source of the wrapper, after checking it compiled without diagnostics. */
    private String generated(String name, String annotations) {
        Compilation result = compile(name, api(annotations));
        assertThat(result.diagnostics()).map(d -> d.getMessage(Locale.ROOT)).isEmpty();
        return result.generatedSource("demo.Api$Resilient");
    }

    /** The body of the public method {@code call}: the outermost layer. */
    private static String publicMethod(String source) {
        int start = source.indexOf("public java.lang.String call(");
        return source.substring(start, source.indexOf("\n    }\n", start));
    }

    @Test
    void theListedLayersMoveTogetherToTheOutermostOfTheirPlaces() {
        String source = generated("timeout-around-retry",
                "@Retry @CircuitBreaker @Timeout(\"1s\") @PolicyOrder({TIMEOUT, RETRY})");

        // Timeout -> Retry -> CircuitBreaker: one timeout for the whole retry loop
        assertThat(publicMethod(source)).contains("this.timeout0").contains("this.r4c$retry0(p0)")
                .doesNotContain("r4c$retry.start()");
        assertThat(source).contains("private java.lang.String r4c$retry0(java.lang.String p0)")
                .contains("return this.r4c$circuitBreaker0(p0);")
                .doesNotContain("r4c$timeout0(");
    }

    @Test
    void layersNotListedKeepTheirDefaultOrderAroundTheMovedOnes() {
        String source = generated("bulkhead-around-retry",
                "@Retry @RateLimiter @Timeout(\"1s\") @Bulkhead @PolicyOrder({BULKHEAD, RETRY})");

        // Bulkhead -> Retry -> RateLimiter -> Timeout
        assertThat(publicMethod(source)).contains("this.bulkhead0").contains("this.r4c$retry0(p0)");
        assertThat(source).contains("return this.r4c$rateLimiter0(p0);").contains("return this.r4c$timeout0(p0);")
                .doesNotContain("r4c$bulkhead0(");
    }

    @Test
    void anOrderThatChangesNothingGeneratesTheDefaultCodeByteForByte() {
        String plain = generated("plain", "@Retry @CircuitBreaker @Timeout(\"1s\")");

        assertThat(generated("same-order",
                "@Retry @CircuitBreaker @Timeout(\"1s\") @PolicyOrder({RETRY, CIRCUIT_BREAKER})"))
                .as("already adjacent in that order").isEqualTo(plain);
        assertThat(generated("adjacent", "@Retry @CircuitBreaker @Timeout(\"1s\") @PolicyOrder({RETRY, TIMEOUT})"))
                .as("the listed layers end up together: Retry -> Timeout -> CircuitBreaker").isNotEqualTo(plain)
                .contains("return this.r4c$timeout0(p0);").contains("r4c$result = this.r4c$circuitBreaker0(p0);");
        assertThat(generated("one-present", "@Retry @CircuitBreaker @Timeout(\"1s\") @PolicyOrder({BULKHEAD, RETRY})"))
                .as("fewer than two of the listed layers are present").isEqualTo(plain);
    }

    @Test
    void theMethodsOrderWinsOverTheTypesAndTheTypesOverABundles() {
        Compilation result = Compilation.compile(workDir.resolve("precedence"), Map.of(
                "demo.Deadline", """
                        package demo;
                        import dev.resilience4cata.annotation.*;
                        import java.lang.annotation.*;
                        @ResiliencePolicy @Retention(RetentionPolicy.CLASS) @Target({ElementType.METHOD, ElementType.TYPE})
                        @PolicyOrder({Layer.CIRCUIT_BREAKER, Layer.RETRY})
                        public @interface Deadline {}
                        """,
                "demo.Api", """
                        package demo;
                        import dev.resilience4cata.annotation.*;
                        import static dev.resilience4cata.annotation.Layer.*;
                        @Deadline
                        @Retry @CircuitBreaker @Timeout("1s")
                        public interface Api {
                            String fromBundle(String id);
                            @PolicyOrder({TIMEOUT, RETRY})
                            String fromMethod(String id);
                        }
                        """));
        assertThat(result.diagnostics()).map(d -> d.getMessage(Locale.ROOT)).isEmpty();
        String source = result.generatedSource("demo.Api$Resilient");

        assertThat(between(source, "fromBundle(java.lang.String p0) {", "\n    }\n")).contains("this.circuitBreaker0");
        assertThat(between(source, "fromMethod(java.lang.String p0) {", "\n    }\n")).contains("this.timeout1");
    }

    @Test
    void theFallbackStaysFusedWithTheRetryOnlyWhenTheRetryIsOutermost() {
        String fused = withFallback("fused", "RETRY, TIMEOUT");
        assertThat(publicMethod(fused)).contains("r4c$retry.start()").contains("this.delegate.recover(p0, r4c$f0)");

        String apart = withFallback("apart", "TIMEOUT, RETRY");
        // the fallback is its own outermost layer around Timeout -> Retry, and the retry gives up by throwing
        assertThat(publicMethod(apart)).contains("this.r4c$timeout0(p0)").doesNotContain("r4c$retry.start()");
        assertThat(between(apart, "private java.lang.String r4c$retry0(", "\n    }\n"))
                .contains("throw r4c$e;").doesNotContain("recover(");
    }

    private String withFallback(String name, String order) {
        Compilation result = compile(name, """
                package demo;
                import dev.resilience4cata.annotation.*;
                import static dev.resilience4cata.annotation.Layer.*;
                public interface Api {
                    @Retry @Timeout("1s") @Fallback(method = "recover") @PolicyOrder({%s})
                    String call(String id);
                    default String recover(String id, Exception e) {
                        return "recovered";
                    }
                }
                """.formatted(order));
        assertThat(result.diagnostics()).map(d -> d.getMessage(Locale.ROOT)).isEmpty();
        return result.generatedSource("demo.Api$Resilient");
    }

    @Test
    void aClassifiedBreakerOutsideTheRetryThrowsItsClassifiersExceptionAsIs() {
        Compilation result = Compilation.compile(workDir.resolve("classifier"), Map.of(
                "demo.Rejects", """
                        package demo;
                        import dev.resilience4cata.Classifier;
                        public class Rejects implements Classifier<String> {
                            @Override
                            public Outcome classify(String value, Throwable failure) {
                                return Outcome.SUCCESS;
                            }
                        }
                        """,
                "demo.Api", api("@Retry @CircuitBreaker(classifier = Rejects.class) @PolicyOrder({CIRCUIT_BREAKER, RETRY})")));
        assertThat(result.diagnostics()).map(d -> d.getMessage(Locale.ROOT)).isEmpty();

        assertThat(result.generatedSource("demo.Api$Resilient")).doesNotContain("ClassifierFailure");
    }

    @Test
    void asyncMethodsNestTheirExecuteAsyncCallsInTheSameOrder() {
        Compilation result = compile("async", """
                package demo;
                import dev.resilience4cata.annotation.*;
                import static dev.resilience4cata.annotation.Layer.*;
                import java.util.concurrent.CompletionStage;
                public interface Api {
                    @Retry @Timeout("1s") @PolicyOrder({TIMEOUT, RETRY})
                    CompletionStage<String> call(String id);
                }
                """);
        assertThat(result.diagnostics()).map(d -> d.getMessage(Locale.ROOT)).isEmpty();

        assertThat(result.generatedSource("demo.Api$Resilient"))
                .contains("return this.timeout0.executeAsync(() -> this.retry0.executeAsync(");
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        assertThat(from).as(start).isNotNegative();
        return source.substring(from, source.indexOf(end, from));
    }
}
