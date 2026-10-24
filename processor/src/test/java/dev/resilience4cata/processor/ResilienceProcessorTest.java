package dev.resilience4cata.processor;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResilienceProcessorTest {

    @TempDir
    Path workDir;

    private Compilation compile(String qualifiedName, String source) {
        return Compilation.compile(workDir, Map.of(qualifiedName, source));
    }

    @Test
    void generatesAWrapperThatCompilesWithoutWarnings() {
        Compilation result = compile("demo.Client", """
                package demo;
                import dev.resilience4cata.annotation.Retry;
                public interface Client {
                    @Retry(maxAttempts = 5, delay = "250ms")
                    String call(String argument) throws java.io.IOException;
                    void other(int... values);
                }
                """);

        assertThat(result.errors()).isEmpty();
        assertThat(result.success()).isTrue();
        String generated = result.generatedSource("demo.Client$Resilient");
        assertThat(generated)
                .contains("public final class Client$Resilient implements demo.Client")
                .contains("\"demo.Client#call(java.lang.String)\"")
                .contains("r4c$b.maxAttempts(5);")
                .contains("r4c$b.delay(java.time.Duration.ofNanos(250000000L));")
                .contains("throws java.io.IOException")
                .contains("public void other(int... p0)");
    }

    @Test
    void interfacesWithoutAnnotationsGetNoWrapper() {
        Compilation result = compile("demo.Plain", "package demo; public interface Plain { int value(); }");

        assertThat(result.success()).isTrue();
        assertThat(Files.exists(result.generated().resolve("demo/Plain$Resilient.java"))).isFalse();
    }

    @Test
    void rejectsInvalidDurations() {
        Compilation result = compile("demo.Client", """
                package demo;
                import dev.resilience4cata.annotation.Retry;
                public interface Client {
                    @Retry(delay = "2 seconds")
                    String call();
                }
                """);

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement().asString()
                .contains("@Retry.delay = \"2 seconds\" is not a valid duration")
                .contains("\"250ms\"");
    }

    @Test
    void rejectsInvalidNumbers() {
        Compilation result = compile("demo.Client", """
                package demo;
                import dev.resilience4cata.annotation.Retry;
                public interface Client {
                    @Retry(maxAttempts = 0, multiplier = 0.5)
                    String call();
                }
                """);

        assertThat(result.errors()).hasSize(2)
                .anySatisfy(e -> assertThat(e).contains("maxAttempts must be at least 1"))
                .anySatisfy(e -> assertThat(e).contains("multiplier must be a finite number >= 1"));
    }

    @Test
    void rejectsInvalidEnabledValues() {
        Compilation result = compile("demo.Client", """
                package demo;
                import dev.resilience4cata.annotation.Retry;
                public interface Client {
                    @Retry(enabled = "yes")
                    String call();
                }
                """);

        assertThat(result.errors()).singleElement().asString().contains("@Retry.enabled must be");
    }

    @Test
    void rejectsClasses() {
        Compilation result = compile("demo.Service", """
                package demo;
                import dev.resilience4cata.annotation.Retry;
                public class Service {
                    @Retry
                    public String call() { return ""; }
                }
                """);

        assertThat(result.errors()).singleElement().asString().contains("only supported on interfaces for now");
    }

    @Test
    void rejectsStaticMethods() {
        Compilation result = compile("demo.Client", """
                package demo;
                import dev.resilience4cata.annotation.Retry;
                public interface Client {
                    @Retry
                    static String call() { return ""; }
                }
                """);

        assertThat(result.errors()).singleElement().asString().contains("static or private methods");
    }

    @Test
    void rejectsAsyncMethodsForNow() {
        Compilation result = compile("demo.Client", """
                package demo;
                import dev.resilience4cata.annotation.Retry;
                public interface Client {
                    @Retry
                    java.util.concurrent.CompletableFuture<String> call();
                }
                """);

        assertThat(result.errors()).singleElement().asString().contains("CompletionStage are not supported yet");
    }

    @Test
    void rejectsClassifiersForNow() {
        Compilation result = compile("demo.Client", """
                package demo;
                import dev.resilience4cata.annotation.Retry;
                public interface Client {
                    @Retry(classifier = String.class)
                    String call();
                }
                """);

        assertThat(result.errors()).singleElement().asString().contains("classifier is not supported yet");
    }

    @Test
    void rejectsComposedAnnotationsWithoutResiliencePolicy() {
        Compilation result = compile("demo.Bundle", """
                package demo;
                import dev.resilience4cata.annotation.Retry;
                @Retry(maxAttempts = 2)
                public @interface Bundle {}
                """);

        assertThat(result.errors()).singleElement().asString().contains("not marked with @ResiliencePolicy");
    }

    @Test
    void reportsConflictsBetweenComposedAnnotations() {
        Compilation result = Compilation.compile(workDir, Map.of(
                "demo.Fast", bundle("Fast", "maxAttempts = 2"),
                "demo.Slow", bundle("Slow", "maxAttempts = 5"),
                "demo.Client", """
                        package demo;
                        public interface Client {
                            @Fast @Slow
                            String call();
                        }
                        """));

        assertThat(result.errors()).singleElement().asString()
                .contains("@Retry.maxAttempts is set to 2 by @Fast and to 5 by @Slow");
    }

    @Test
    void directAnnotationResolvesAComposedConflict() {
        Compilation result = Compilation.compile(workDir, Map.of(
                "demo.Fast", bundle("Fast", "maxAttempts = 2"),
                "demo.Client", """
                        package demo;
                        import dev.resilience4cata.annotation.Retry;
                        public interface Client {
                            @Fast @Retry(maxAttempts = 9)
                            String call();
                        }
                        """));

        assertThat(result.errors()).isEmpty();
        assertThat(result.generatedSource("demo.Client$Resilient")).contains("r4c$b.maxAttempts(9);")
                .doesNotContain("maxAttempts(2)");
    }

    @Test
    void stripsTypeAnnotationsFromGeneratedSignatures() {
        Compilation result = Compilation.compile(workDir, Map.of(
                "demo.Nullable", """
                        package demo;
                        import java.lang.annotation.*;
                        @Target(ElementType.TYPE_USE) @Retention(RetentionPolicy.CLASS)
                        public @interface Nullable {}
                        """,
                "demo.Client", """
                        package demo;
                        import dev.resilience4cata.annotation.Retry;
                        public interface Client {
                            @Retry
                            @Nullable String call(java.util.List<@Nullable String> values);
                        }
                        """));

        assertThat(result.errors()).isEmpty();
        assertThat(result.generatedSource("demo.Client$Resilient"))
                .contains("public java.lang.String call(java.util.List<java.lang.String> p0)");
    }

    @Test
    void nestsTheCircuitBreakerInsideTheRetry() {
        Compilation result = compile("demo.Client", """
                package demo;
                import dev.resilience4cata.annotation.CircuitBreaker;
                import dev.resilience4cata.annotation.Retry;
                public interface Client {
                    @Retry(maxAttempts = 3)
                    @CircuitBreaker(windowSize = 10, failureRateThreshold = 25, waitInOpen = "30s")
                    String call();
                    @CircuitBreaker
                    void fireAndForget();
                }
                """);

        assertThat(result.errors()).isEmpty();
        String generated = result.generatedSource("demo.Client$Resilient");
        assertThat(generated)
                .contains("r4c$b.windowSize(10);")
                .contains("r4c$b.failureRateThreshold(25.0f);")
                .contains("r4c$b.waitInOpen(java.time.Duration.ofNanos(30000000000L));");
        int retryLoop = generated.indexOf("while (true)");
        int acquire = generated.indexOf("r4c$cb.acquire()");
        assertThat(retryLoop).isPositive().isLessThan(acquire);
    }

    @Test
    void rejectsInvalidCircuitBreakerValues() {
        Compilation result = compile("demo.Client", """
                package demo;
                import dev.resilience4cata.annotation.CircuitBreaker;
                public interface Client {
                    @CircuitBreaker(windowSize = 0, failureRateThreshold = 150, waitInOpen = "soon")
                    String call();
                }
                """);

        assertThat(result.errors()).hasSize(3)
                .anySatisfy(e -> assertThat(e).contains("@CircuitBreaker.windowSize must be at least 1"))
                .anySatisfy(e -> assertThat(e).contains("@CircuitBreaker.failureRateThreshold must be a percentage"))
                .anySatisfy(e -> assertThat(e).contains("@CircuitBreaker.waitInOpen = \"soon\" is not a valid duration"));
    }

    @Test
    void supportsTimeWindows() {
        Compilation result = compile("demo.Client", """
                package demo;
                import dev.resilience4cata.annotation.CircuitBreaker;
                import dev.resilience4cata.annotation.WindowType;
                public interface Client {
                    @CircuitBreaker(windowType = WindowType.TIME)
                    String call();
                }
                """);

        assertThat(result.errors()).isEmpty();
        assertThat(result.generatedSource("demo.Client$Resilient"))
                .contains("r4c$b.windowType(dev.resilience4cata.CircuitBreakerConfig.WindowType.TIME);");
    }

    @Test
    void nestsTheTimeoutInsideTheCircuitBreakerAsPrivateLayers() {
        Compilation result = compile("demo.Client", """
                package demo;
                import dev.resilience4cata.annotation.CircuitBreaker;
                import dev.resilience4cata.annotation.Retry;
                import dev.resilience4cata.annotation.Timeout;
                public interface Client {
                    @Retry @CircuitBreaker @Timeout("2s")
                    String call(int id) throws java.io.IOException;
                    @Timeout("250ms")
                    void ping();
                }
                """);

        assertThat(result.errors()).isEmpty();
        String generated = result.generatedSource("demo.Client$Resilient");
        assertThat(generated)
                .contains("return this.r4c$circuitBreaker0(p0);")
                .contains("private java.lang.String r4c$circuitBreaker0(int p0) throws java.io.IOException {")
                .contains("final java.lang.String r4c$result = this.r4c$timeout0(p0);")
                .contains("private java.lang.String r4c$timeout0(int p0) throws java.io.IOException {")
                .contains("r4c$result = this.delegate.call(p0);")
                .contains("r4c$b.timeout(java.time.Duration.ofNanos(2000000000L));")
                .contains("r4c$b.timeout(java.time.Duration.ofNanos(250000000L));");
    }

    @Test
    void rejectsInvalidTimeouts() {
        Compilation result = compile("demo.Client", """
                package demo;
                import dev.resilience4cata.annotation.Timeout;
                public interface Client {
                    @Timeout("0")
                    String zero();
                    @Timeout("soon")
                    String invalid();
                }
                """);

        assertThat(result.errors()).hasSize(2)
                .anySatisfy(e -> assertThat(e).contains("@Timeout must be positive"))
                .anySatisfy(e -> assertThat(e).contains("@Timeout(\"soon\") is not a valid duration"));
    }

    private static String bundle(String name, String attributes) {
        return """
                package demo;
                import dev.resilience4cata.annotation.ResiliencePolicy;
                import dev.resilience4cata.annotation.Retry;
                import java.lang.annotation.*;
                @ResiliencePolicy
                @Retention(RetentionPolicy.CLASS)
                @Target({ElementType.METHOD, ElementType.TYPE})
                @Retry(%s)
                public @interface %s {}
                """.formatted(attributes, name);
    }
}
