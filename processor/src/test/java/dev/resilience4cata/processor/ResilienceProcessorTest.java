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

    @Test
    void putsTheBulkheadInnermost() {
        Compilation result = compile("demo.Client", """
                package demo;
                import dev.resilience4cata.annotation.Bulkhead;
                import dev.resilience4cata.annotation.Timeout;
                public interface Client {
                    @Timeout("2s") @Bulkhead(maxConcurrent = 10, maxWait = "100ms")
                    String call();
                    @Bulkhead(name = "pool")
                    void fire();
                }
                """);

        assertThat(result.errors()).isEmpty();
        assertThat(result.generatedSource("demo.Client$Resilient"))
                .contains("r4c$result = this.r4c$bulkhead0();")
                .contains("private java.lang.String r4c$bulkhead0() {")
                .contains("return this.delegate.call();")
                .contains("r4c$b.maxConcurrent(10);")
                .contains("r4c$b.maxWait(java.time.Duration.ofNanos(100000000L));")
                .contains("\"pool\"");
    }

    @Test
    void rejectsInvalidBulkheadValues() {
        Compilation result = compile("demo.Client", """
                package demo;
                import dev.resilience4cata.annotation.Bulkhead;
                public interface Client {
                    @Bulkhead(maxConcurrent = 0, maxWait = "forever")
                    String call();
                }
                """);

        assertThat(result.errors()).hasSize(2)
                .anySatisfy(e -> assertThat(e).contains("@Bulkhead.maxConcurrent must be at least 1"))
                .anySatisfy(e -> assertThat(e).contains("@Bulkhead.maxWait = \"forever\" is not a valid duration"));
    }

    @Test
    void putsTheRateLimiterBetweenTheBreakerAndTheTimeout() {
        Compilation result = compile("demo.Client", """
                package demo;
                import dev.resilience4cata.annotation.CircuitBreaker;
                import dev.resilience4cata.annotation.RateLimiter;
                import dev.resilience4cata.annotation.Timeout;
                public interface Client {
                    @Timeout("2s") @RateLimiter(permits = 10, period = "1s", maxWait = "50ms") @CircuitBreaker
                    String call();
                    @RateLimiter(name = "api")
                    void fire();
                }
                """);

        assertThat(result.errors()).isEmpty();
        assertThat(result.generatedSource("demo.Client$Resilient"))
                .contains("final java.lang.String r4c$result = this.r4c$rateLimiter0();")
                .contains("private java.lang.String r4c$rateLimiter0() {")
                .contains("return this.r4c$timeout0();")
                .contains("r4c$b.permits(10);")
                .contains("r4c$b.period(java.time.Duration.ofNanos(1000000000L));")
                .contains("r4c$b.maxWait(java.time.Duration.ofNanos(50000000L));")
                .contains("this.delegate.fire();")
                .contains("\"api\"");
    }

    @Test
    void rejectsInvalidRateLimiterValues() {
        Compilation result = compile("demo.Client", """
                package demo;
                import dev.resilience4cata.annotation.RateLimiter;
                public interface Client {
                    @RateLimiter(permits = 0, period = "0")
                    String call();
                    @RateLimiter(period = "often")
                    String other();
                }
                """);

        assertThat(result.errors()).hasSize(3)
                .anySatisfy(e -> assertThat(e).contains("@RateLimiter.permits must be at least 1"))
                .anySatisfy(e -> assertThat(e).contains("@RateLimiter.period must be positive"))
                .anySatisfy(e -> assertThat(e).contains("@RateLimiter.period = \"often\" is not a valid duration"));
    }

    @Test
    void callsATypedHandlerAndRethrowsItsDeclaredExceptions() {
        Compilation result = compile("demo.Client", """
                package demo;
                import dev.resilience4cata.FallbackContext;
                import dev.resilience4cata.annotation.Fallback;
                import dev.resilience4cata.annotation.Timeout;
                public interface Client {
                    @Fallback(value = Cached.class, skipOn = IllegalArgumentException.class) @Timeout("1s")
                    String find(String id, int version) throws java.io.IOException;
                    @Fallback(Count.class)
                    int count();
                    @Fallback(Count.class)
                    void touch();

                    final class Cached implements dev.resilience4cata.FallbackHandler<String> {
                        @Override
                        public String apply(FallbackContext context) {
                            return "cached " + context.argument(0, String.class);
                        }
                    }

                    final class Count implements dev.resilience4cata.FallbackHandler<Integer> {
                        @Override
                        public Integer apply(FallbackContext context) {
                            return 0;
                        }
                    }
                }
                """);

        assertThat(result.errors()).isEmpty();
        assertThat(result.generatedSource("demo.Client$Resilient"))
                .contains("private final demo.Client.Cached fallbackHandler0;")
                .contains("this.fallbackHandler0 = new demo.Client.Cached();")
                .contains("java.util.List.of(java.lang.Throwable.class), java.util.List.of(java.lang.IllegalArgumentException.class)")
                .contains("return this.r4c$timeout0(p0, p1);")
                .contains("this.fallback0.context(r4c$e, 1, new java.lang.Object[] {p0, p1});")
                .contains("if (r4c$x instanceof java.io.IOException r4c$d0) {")
                .contains("return this.fallbackHandler1.apply(r4c$context);")
                .contains("this.fallbackHandler2.apply(r4c$context);");
    }

    @Test
    void picksTheMostSpecificSiblingMethodFirst() {
        Compilation result = compile("demo.Client", """
                package demo;
                import dev.resilience4cata.annotation.Fallback;
                public interface Client {
                    @Fallback(method = "recover")
                    String find(String id) throws java.io.IOException;

                    default String recover(String id, java.io.IOException e) { return "io"; }
                    default String recover(String id, java.io.FileNotFoundException e) { return "missing"; }
                    static String recover(String id, IllegalStateException e) { return "state"; }
                    String recover(String id);
                    default String recover(int other) { return "unrelated"; }
                }
                """);

        assertThat(result.errors()).isEmpty();
        String generated = result.generatedSource("demo.Client$Resilient");
        int missing = generated.indexOf("instanceof java.io.FileNotFoundException");
        int io = generated.indexOf("instanceof java.io.IOException");
        assertThat(missing).isPositive().isLessThan(io);
        assertThat(generated)
                .contains("return demo.Client.recover(p0, r4c$f")
                .contains("return this.delegate.recover(p0);");
    }

    @Test
    void fusesTheFallbackIntoTheRetryLoopToCountAttempts() {
        Compilation result = compile("demo.Client", """
                package demo;
                import dev.resilience4cata.annotation.Fallback;
                import dev.resilience4cata.annotation.Retry;
                public interface Client {
                    @Retry(maxAttempts = 3) @Fallback(Empty.class)
                    String find(String id);
                    @Retry(maxAttempts = 3) @Fallback(method = "recover")
                    String other(String id);
                    default String recover(String id, Throwable e) { return "fallback"; }

                    final class Empty implements dev.resilience4cata.FallbackHandler<String> {
                        @Override
                        public String apply(dev.resilience4cata.FallbackContext context) {
                            return "";
                        }
                    }
                }
                """);

        assertThat(result.errors()).isEmpty();
        assertThat(result.generatedSource("demo.Client$Resilient"))
                .contains("this.fallback0.context(r4c$e, r4c$attempts, new java.lang.Object[] {p0});")
                .contains("return this.delegate.recover(p0, r4c$e);")
                .doesNotContain("r4c$fallback0(");
    }

    @Test
    void rejectsInvalidFallbacks() {
        Compilation result = compile("demo.Client", """
                package demo;
                import dev.resilience4cata.FallbackContext;
                import dev.resilience4cata.annotation.Fallback;
                public interface Client {
                    @Fallback
                    String none();
                    @Fallback(value = Text.class, method = "x")
                    String both();
                    @Fallback(Text.class)
                    int wrongType();
                    @Fallback(NoConstructor.class)
                    String noConstructor();
                    @Fallback(method = "recover")
                    String find(String id);
                    @Fallback(Text.class)
                    <T> T generic();

                    private String recover(String id) { return ""; }
                    default Object recover(String id, RuntimeException e) { return ""; }
                    default String recover(String id, Exception e) throws Exception { return ""; }

                    final class Text implements dev.resilience4cata.FallbackHandler<String> {
                        @Override
                        public String apply(FallbackContext context) {
                            return "";
                        }
                    }

                    final class NoConstructor implements dev.resilience4cata.FallbackHandler<String> {
                        private NoConstructor() {
                        }

                        @Override
                        public String apply(FallbackContext context) {
                            return "";
                        }
                    }
                }
                """);

        assertThat(result.errors()).hasSize(6)
                .anySatisfy(e -> assertThat(e).contains("@Fallback needs exactly one of"))
                .anySatisfy(e -> assertThat(e).contains("implements FallbackHandler<java.lang.String>, but wrongType returns int."
                        + " Implement FallbackHandler<java.lang.Integer>"))
                .anySatisfy(e -> assertThat(e).contains("needs a public no-argument constructor"))
                .anySatisfy(e -> assertThat(e).contains("no usable method recover")
                        .contains("recover(java.lang.String) is private")
                        .contains("returns java.lang.Object, which is not assignable to java.lang.String")
                        .contains("throws java.lang.Exception, which find does not declare"))
                .anySatisfy(e -> assertThat(e).contains("generic methods is not supported yet"));
    }

    @Test
    void rejectsAHandlerTheGeneratedClassCannotCreate() {
        Compilation result = compile("demo.Client", """
                package demo;
                import dev.resilience4cata.annotation.Fallback;
                public interface Client {
                    @Fallback(Abstract.class)
                    String call();
                    @Fallback(Holder.Inner.class)
                    String inner();

                    abstract class Abstract implements dev.resilience4cata.FallbackHandler<String> {
                    }
                }

                class Holder {
                    class Inner implements dev.resilience4cata.FallbackHandler<String> {
                        @Override
                        public String apply(dev.resilience4cata.FallbackContext context) {
                            return "";
                        }
                    }
                }
                """);

        assertThat(result.errors()).hasSize(2)
                .anySatisfy(e -> assertThat(e).contains("the handler must not be abstract"))
                .anySatisfy(e -> assertThat(e).contains("a nested handler class must be static"));
    }

    @Test
    void generatesOneExpressionForAsyncMethods() {
        Compilation result = compile("demo.Client", """
                package demo;
                import dev.resilience4cata.annotation.CircuitBreaker;
                import dev.resilience4cata.annotation.Fallback;
                import dev.resilience4cata.annotation.Retry;
                import dev.resilience4cata.annotation.Timeout;
                import java.util.concurrent.CompletableFuture;
                import java.util.concurrent.CompletionStage;
                public interface Client {
                    @Retry @CircuitBreaker @Timeout("1s")
                    CompletableFuture<String> find(String id) throws java.io.IOException;
                    @Fallback(method = "recover") @Timeout("1s")
                    CompletionStage<java.util.List<String>> list(int page);
                    default java.util.List<String> recover(int page) { return java.util.List.of(); }
                    CompletableFuture<String> plain(String id);
                }
                """);

        assertThat(result.errors()).isEmpty();
        assertThat(result.generatedSource("demo.Client$Resilient"))
                .contains("return this.retry0.executeAsync(() -> this.circuitBreaker0.executeAsync(()"
                        + " -> this.timeout0.executeAsync(() -> this.delegate.find(p0))));")
                .contains("return this.fallback1.executeAsync(() -> this.timeout1.executeAsync(() -> this.delegate.list(p0)),"
                        + " (r4c$e, r4c$attempts) -> this.r4c$recover1(r4c$e, r4c$attempts, p0));")
                .contains("private java.util.concurrent.CompletionStage<java.util.List<java.lang.String>> r4c$recover1("
                        + "java.lang.Throwable r4c$e, int r4c$attempts, int p0) throws java.lang.Throwable {")
                .contains("return java.util.concurrent.CompletableFuture.completedFuture(this.delegate.recover(p0));")
                .contains("return this.delegate.plain(p0);");
    }

    @Test
    void rejectsUnsupportedAsyncReturnTypes() {
        Compilation result = compile("demo.Client", """
                package demo;
                import dev.resilience4cata.annotation.Fallback;
                import dev.resilience4cata.annotation.Retry;
                import java.util.concurrent.CompletableFuture;
                import java.util.concurrent.CompletionStage;
                public interface Client {
                    @Retry
                    CompletionStage<? extends Number> wildcard();
                    @Retry
                    Custom custom();
                    @Fallback(method = "recover")
                    CompletableFuture<String> wrongFallback(String id);
                    default Integer recover(String id) { return 0; }
                    @Fallback(Text.class)
                    CompletableFuture<Integer> wrongHandler();

                    abstract class Custom extends CompletableFuture<String> {
                    }

                    final class Text implements dev.resilience4cata.FallbackHandler<String> {
                        @Override
                        public String apply(dev.resilience4cata.FallbackContext context) {
                            return "";
                        }
                    }
                }
                """);

        assertThat(result.errors()).hasSize(4)
                .anySatisfy(e -> assertThat(e).contains("must return CompletionStage<T> or CompletableFuture<T>")
                        .contains("returns java.util.concurrent.CompletionStage<? extends java.lang.Number>"))
                .anySatisfy(e -> assertThat(e).contains("returns demo.Client.Custom"))
                .anySatisfy(e -> assertThat(e).contains("recover(java.lang.String) returns java.lang.Integer, which is neither"
                        + " java.lang.String nor a CompletionStage of it"))
                .anySatisfy(e -> assertThat(e).contains("Implement FallbackHandler<java.lang.Integer> or"
                        + " FallbackHandler<java.util.concurrent.CompletionStage<java.lang.Integer>>"));
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
