package dev.resilience4cata.processor;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code @AdaptiveLimit}: its layer between the rate limiter and the timeout, sync and async, and its compile errors. */
class AdaptiveLimitProcessorTest {

    @TempDir
    Path workDir;

    private Compilation compile(String qualifiedName, String source) {
        return Compilation.compile(workDir, Map.of(qualifiedName, source));
    }

    @Test
    void generatesTheLimitBetweenTheRateLimiterAndTheTimeout() {
        Compilation result = compile("demo.OrderApi", """
                package demo;
                import dev.resilience4cata.annotation.AdaptiveLimit;
                import dev.resilience4cata.annotation.Bulkhead;
                import dev.resilience4cata.annotation.LimitAlgorithm;
                import dev.resilience4cata.annotation.RateLimiter;
                import dev.resilience4cata.annotation.Timeout;
                public interface OrderApi {
                    @RateLimiter(permits = 10)
                    @AdaptiveLimit(algorithm = LimitAlgorithm.AIMD, initialLimit = 10, minLimit = 2, maxLimit = 50,
                            window = "500ms", dropOn = java.io.IOException.class, ignoreOn = java.io.FileNotFoundException.class)
                    @Timeout("2s") @Bulkhead(maxConcurrent = 60)
                    String order(String id) throws java.io.IOException;
                    @AdaptiveLimit(name = "shared", enabled = "false")
                    void cancel(String id);
                }
                """);

        assertThat(result.errors()).isEmpty();
        String generated = result.generatedSource("demo.OrderApi$Resilient");
        assertThat(generated)
                .contains("private final dev.resilience4cata.generated.AdaptiveLimitPolicy adaptiveLimit0;")
                .contains("this.adaptiveLimit0 = registry.adaptiveLimitPolicy(\"demo.OrderApi#order(java.lang.String)\", \"\", \"\", r4c$b -> {")
                .contains("r4c$b.algorithm(dev.resilience4cata.AdaptiveLimitConfig.Algorithm.AIMD);")
                .contains("r4c$b.initialLimit(10);")
                .contains("r4c$b.minLimit(2);")
                .contains("r4c$b.maxLimit(50);")
                .contains("r4c$b.window(java.time.Duration.ofNanos(500000000L));")
                .contains("r4c$b.dropOn(java.util.List.of(java.io.IOException.class));")
                .contains("r4c$b.ignoreOn(java.util.List.of(java.io.FileNotFoundException.class));")
                .contains("this.adaptiveLimit1 = registry.adaptiveLimitPolicy(\"demo.OrderApi#cancel(java.lang.String)\", \"shared\", \"\", r4c$b -> {")
                .contains("r4c$b.enabled(false);");
        assertThat(flat(generated))
                .contains(flat("""
                        throw r4c$rateLimiter.rejection();
                        }
                        return this.r4c$adaptiveLimit0(p0);
                        """))
                .contains(flat("""
                        private java.lang.String r4c$adaptiveLimit0(java.lang.String p0) throws java.io.IOException {
                        final dev.resilience4cata.generated.AdaptiveLimitPolicy r4c$adaptiveLimit = this.adaptiveLimit0;
                        final long r4c$start = r4c$adaptiveLimit.acquire();
                        final java.lang.String r4c$result;
                        try {
                        r4c$result = this.r4c$timeout0(p0);
                        } catch (java.lang.Throwable r4c$e) {
                        r4c$adaptiveLimit.onFailure(r4c$start, r4c$e);
                        throw r4c$e;
                        }
                        r4c$adaptiveLimit.onSuccess(r4c$start);
                        return r4c$result;
                        }
                        """))
                .contains(flat("""
                        try {
                        this.delegate.cancel(p0);
                        } catch (java.lang.Throwable r4c$e) {
                        r4c$adaptiveLimit.onFailure(r4c$start, r4c$e);
                        throw r4c$e;
                        }
                        r4c$adaptiveLimit.onSuccess(r4c$start);
                        return;
                        """));
    }

    /** The source with every run of whitespace replaced by one space, to compare code regardless of indentation. */
    private static String flat(String source) {
        return source.replaceAll("\\s+", " ").trim();
    }

    @Test
    void anAsyncMethodNestsItsExecuteAsyncInTheSameOrder() {
        Compilation result = compile("demo.AsyncApi", """
                package demo;
                import dev.resilience4cata.annotation.AdaptiveLimit;
                import dev.resilience4cata.annotation.CircuitBreaker;
                import dev.resilience4cata.annotation.Timeout;
                import java.util.concurrent.CompletionStage;
                @AdaptiveLimit
                public interface AsyncApi {
                    @CircuitBreaker @Timeout("1s")
                    CompletionStage<String> fetch(String id);
                }
                """);

        assertThat(result.errors()).isEmpty();
        assertThat(result.generatedSource("demo.AsyncApi$Resilient"))
                .contains("this.adaptiveLimit0 = registry.adaptiveLimitPolicy(\"demo.AsyncApi#fetch(java.lang.String)\", \"\", \"\", null);")
                .contains("return this.circuitBreaker0.executeAsync(() -> this.adaptiveLimit0.executeAsync(() -> "
                        + "this.timeout0.executeAsync(() -> this.delegate.fetch(p0))));");
    }

    @Test
    void worksOnAnnotatedClassesAndInBundles() {
        Compilation result = compile("demo.Inventory", """
                package demo;
                import dev.resilience4cata.annotation.AdaptiveLimit;
                import dev.resilience4cata.annotation.ResiliencePolicy;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                public class Inventory {
                    @ResiliencePolicy
                    @Retention(RetentionPolicy.CLASS)
                    @AdaptiveLimit(maxLimit = 80, window = "2s")
                    public @interface Backend {
                    }

                    @Backend
                    @AdaptiveLimit(maxLimit = 90)
                    public int stock(String sku) {
                        return 1;
                    }
                }
                """);

        assertThat(result.errors()).isEmpty();
        assertThat(result.generatedSource("demo.Inventory$Resilient"))
                .contains("r4c$b.maxLimit(90);")
                .contains("r4c$b.window(java.time.Duration.ofNanos(2000000000L));")
                .contains("r4c$result = super.stock(sku);");
    }

    @Test
    void reportsBoundsOutOfOrderAndWindowsOutOfRange() {
        Compilation result = compile("demo.BadApi", """
                package demo;
                import dev.resilience4cata.annotation.AdaptiveLimit;
                public interface BadApi {
                    @AdaptiveLimit(minLimit = 10, maxLimit = 5)
                    String a();
                    @AdaptiveLimit(initialLimit = 300, maxLimit = 200)
                    String b();
                    @AdaptiveLimit(window = "5ms")
                    String c();
                    @AdaptiveLimit(window = "2h")
                    String d();
                    @AdaptiveLimit(minLimit = 0)
                    String e();
                    @AdaptiveLimit(window = "soon")
                    String f();
                }
                """);

        assertThat(result.errors()).containsExactlyInAnyOrder(
                "[R4C-ATR-009] @AdaptiveLimit.minLimit (10) must not be greater than @AdaptiveLimit.maxLimit (5): the limit"
                        + " stays between minLimit and maxLimit and starts at initialLimit.",
                "[R4C-ATR-009] @AdaptiveLimit.initialLimit (300) must not be greater than @AdaptiveLimit.maxLimit (200): the"
                        + " limit stays between minLimit and maxLimit and starts at initialLimit.",
                "[R4C-ATR-009] @AdaptiveLimit.window must be between 10ms and 1h, was \"5ms\"",
                "[R4C-ATR-009] @AdaptiveLimit.window must be between 10ms and 1h, was \"2h\"",
                "[R4C-ATR-003] @AdaptiveLimit.minLimit must be at least 1, was 0",
                "[R4C-ATR-001] @AdaptiveLimit.window = \"soon\" is not a valid duration: " + invalidDurationDetail("soon")
                        + ". Use " + Durations.GRAMMAR + ".");
    }

    private static String invalidDurationDetail(String value) {
        try {
            Durations.parseNanos(value);
            throw new AssertionError(value + " parsed");
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    @Test
    void methodsWithoutTheAnnotationAreUnchanged() {
        String source = """
                package demo;
                import dev.resilience4cata.annotation.Bulkhead;
                public interface PlainApi {
                    @Bulkhead(maxConcurrent = 3)
                    String get(String id);
                }
                """;
        String generated = compile("demo.PlainApi", source).generatedSource("demo.PlainApi$Resilient");
        assertThat(generated).doesNotContain("adaptiveLimit").doesNotContain("r4c$start");
    }
}
