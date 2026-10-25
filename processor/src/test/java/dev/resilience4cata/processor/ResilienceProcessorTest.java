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
        assertThat(result.errorCodes()).containsExactlyInAnyOrder("R4C-ATR-001");
        assertThat(result.errors()).singleElement().asString()
                .contains("@Retry.delay = \"2 seconds\" is not a valid duration: unrecognized format. Use a number")
                .contains("\"250ms\"")
                .satisfies(e -> assertThat(e.split("a number followed by", -1)).as("the grammar is stated once").hasSize(2));
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

        assertThat(result.errorCodes()).containsExactlyInAnyOrder("R4C-ATR-003", "R4C-ATR-004");
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

        assertThat(result.errorCodes()).containsExactlyInAnyOrder("R4C-ATR-006");
        assertThat(result.errors()).singleElement().asString().contains("@Retry.enabled must be");
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

        assertThat(result.errorCodes()).containsExactlyInAnyOrder("R4C-APT-002");
        assertThat(result.errors()).containsExactly("[R4C-APT-002] Resilience annotations cannot be used on static or"
                + " private methods: calls to them cannot be intercepted. Annotate a non-private instance method instead.");
    }

    @Test
    void suggestsTheClosestNameForAMissingFallbackMethod() {
        Compilation result = compile("demo.UserClient", """
                package demo;
                import dev.resilience4cata.annotation.Fallback;
                public interface UserClient {
                    @Fallback(method = "findFallbak")
                    String find(String id);
                    @Fallback(method = "unrelated")
                    String other(String id);

                    default String findFallback(String id, Exception e) { return ""; }
                }
                """);

        assertThat(result.errors()).containsExactly(
                "[R4C-FB-002] @Fallback(method = \"findFallbak\") on find: no method named findFallbak in UserClient."
                        + " Did you mean findFallback? Expected a default or static method java.lang.String"
                        + " findFallbak(java.lang.String, <a Throwable type> e), where the exception parameter is optional.",
                "[R4C-FB-002] @Fallback(method = \"unrelated\") on other: no method named unrelated in UserClient."
                        + " Expected a default or static method java.lang.String unrelated(java.lang.String, <a Throwable"
                        + " type> e), where the exception parameter is optional.");
    }

    @Test
    void generatesClassifiedLayersThatCompileWithoutWarnings() {
        Compilation result = compile("demo.Client", """
                package demo;
                import dev.resilience4cata.Classifier;
                import dev.resilience4cata.annotation.CircuitBreaker;
                import dev.resilience4cata.annotation.Fallback;
                import dev.resilience4cata.annotation.Retry;
                import java.util.concurrent.CompletableFuture;
                import java.util.concurrent.CompletionStage;
                public interface Client {
                    @Retry(classifier = Status.class) @CircuitBreaker(classifier = Status.class)
                    Integer both(String id) throws java.io.IOException;
                    @Retry(classifier = Status.class) @CircuitBreaker(classifier = Any.class)
                    @Fallback(method = "recover")
                    Integer different(String id) throws java.io.IOException;
                    @Retry(classifier = Any.class) @Fallback(Zero.class)
                    int primitive();
                    @CircuitBreaker(classifier = Any.class)
                    void nothing();
                    @Retry(classifier = Any.class)
                    <T> T generic(T value);
                    @Retry(classifier = Status.class) @CircuitBreaker(classifier = Status.class) @Fallback(method = "later")
                    CompletionStage<Integer> async(String id);
                    @Retry(classifier = Status.class)
                    CompletableFuture<Integer> future();
                    @CircuitBreaker(classifier = Status.class)
                    CompletionStage<Integer> breakerOnly();
                    @Retry @CircuitBreaker(classifier = Status.class) @Fallback(method = "recover")
                    Integer plainRetry(String id) throws java.io.IOException;

                    default Integer recover(String id, java.io.IOException e) { return -1; }
                    default Integer recover(String id) { return -2; }
                    default Integer later(String id) { return -3; }

                    final class Status implements Classifier<Integer> {
                        @Override
                        public Outcome classify(Integer result, Throwable failure) {
                            return failure != null || result >= 500 ? Outcome.FAILURE : Outcome.SUCCESS;
                        }
                    }

                    final class Any implements Classifier<Object> {
                        @Override
                        public Outcome classify(Object result, Throwable failure) {
                            return Outcome.SUCCESS;
                        }
                    }

                    final class Zero implements dev.resilience4cata.FallbackHandler<Integer> {
                        @Override
                        public Integer apply(dev.resilience4cata.FallbackContext context) {
                            return 0;
                        }
                    }
                }
                """);

        assertThat(result.errors()).isEmpty();
        assertThat(result.success()).isTrue();
        String generated = result.generatedSource("demo.Client$Resilient");
        assertThat(generated)
                // one instance per method; the breaker shares the retry's when both use the same class
                .contains("this.retryClassifier0 = new demo.Client.Status();")
                .doesNotContain("circuitBreakerClassifier0")
                .contains("this.retryClassifier1 = new demo.Client.Status();")
                .contains("this.circuitBreakerClassifier1 = new demo.Client.Any();")
                .contains("r4c$retry.onFailure(++r4c$attempts, r4c$e, r4c$start, r4c$verdict)")
                .contains("r4c$retry.onFailedResult(++r4c$attempts, r4c$start)")
                .contains("r4c$cb.onFailure(r4c$token, r4c$started, r4c$e, r4c$verdict);")
                .contains("r4c$cb.onResult(r4c$token, r4c$started, r4c$verdict);")
                // a classifier exception goes to the fallback, counted as one more attempt
                .contains("this.fallback2.context(r4c$c, r4c$attempts + 1, new java.lang.Object[] {});")
                .contains("if (r4c$c instanceof java.io.IOException r4c$f0) {")
                .contains("this.retry5.executeAsync(() -> this.circuitBreaker5.executeAsync(() -> this.delegate.async(p0),"
                        + " this.retryClassifier5, true), this.retryClassifier5, this.fallback5,")
                .contains("this.retry6.executeAsync(() -> this.delegate.future(), this.retryClassifier6, null, null)")
                .contains("this.circuitBreaker7.executeAsync(() -> this.delegate.breakerOnly(), this.circuitBreakerClassifier7)");
        // inside a retry, a breaker's classifier exception is wrapped, and the retry ends the call with it
        // (methods 0, 1 and 8); a breaker without a retry (method 3) rethrows it as is
        assertThat(generated.split("throw new dev.resilience4cata.generated.ClassifierFailure\\(r4c\\$c\\);", -1))
                .hasSize(1 + 3 * 2);
        assertThat(generated.split("catch \\(dev.resilience4cata.generated.ClassifierFailure r4c\\$w\\)", -1)).hasSize(1 + 3);
        assertThat(generated)
                .contains("            } catch (dev.resilience4cata.generated.ClassifierFailure r4c$w) {\n"
                        + "                try {\n"
                        + "                    throw r4c$w.failure();\n"
                        + "                } catch (java.lang.Throwable r4c$c) {\n"
                        + "                    r4c$retry.onClassifierFailure(r4c$attempts + 1, r4c$c);\n"
                        + "                    if (!this.fallback8.handles(r4c$c)) {\n")
                .contains("        } catch (java.lang.Throwable r4c$c) {\n"
                        + "            r4c$cb.onResult(r4c$token, r4c$started, dev.resilience4cata.Classifier.Outcome.FAILURE);\n"
                        + "            throw r4c$c;\n");
    }

    @Test
    void mergesClassifiersLikeOtherAttributes() {
        Compilation result = compile("demo.Client", """
                package demo;
                import dev.resilience4cata.Classifier;
                import dev.resilience4cata.annotation.ResiliencePolicy;
                import dev.resilience4cata.annotation.Retry;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                @Retry(classifier = Client.Lenient.class)
                public interface Client {
                    String inherited();
                    @Retry(classifier = Client.Strict.class)
                    String overridden();
                    @Bundle
                    String bundled();

                    @ResiliencePolicy
                    @Retry(classifier = Strict.class)
                    @Retention(RetentionPolicy.CLASS)
                    @interface Bundle {
                    }

                    final class Lenient implements Classifier<Object> {
                        @Override
                        public Outcome classify(Object result, Throwable failure) {
                            return Outcome.SUCCESS;
                        }
                    }

                    final class Strict implements Classifier<CharSequence> {
                        @Override
                        public Outcome classify(CharSequence result, Throwable failure) {
                            return Outcome.FAILURE;
                        }
                    }
                }
                """);

        assertThat(result.errors()).isEmpty();
        assertThat(result.generatedSource("demo.Client$Resilient"))
                .contains("this.retryClassifier0 = new demo.Client.Lenient();")
                .contains("this.retryClassifier1 = new demo.Client.Strict();")
                .contains("this.retryClassifier2 = new demo.Client.Strict();");
    }

    @Test
    void rejectsInvalidClassifiers() {
        Compilation result = compile("demo.Client", """
                package demo;
                import dev.resilience4cata.Classifier;
                import dev.resilience4cata.annotation.CircuitBreaker;
                import dev.resilience4cata.annotation.Retry;
                import java.util.concurrent.CompletionStage;
                public interface Client {
                    @Retry(classifier = String.class)
                    String notAClassifier();
                    @Retry(classifier = Numbers.class)
                    String wrongType();
                    @CircuitBreaker(classifier = Numbers.class)
                    CompletionStage<String> wrongAsyncType();
                    @Retry(classifier = Abstract.class)
                    Integer isAbstract();
                    @Retry(classifier = NoConstructor.class)
                    Integer noConstructor();
                    @Retry(classifier = Generic.class)
                    Integer generic();
                    @Retry(classifier = Raw.class)
                    Integer raw();
                    @Retry(classifier = Numbers.class, retryOn = RuntimeException.class)
                    Integer withRetryOn();
                    @CircuitBreaker(classifier = Numbers.class, recordOn = RuntimeException.class)
                    Integer withRecordOn();
                    @Retry(classifier = Numbers.class, abortOn = RuntimeException.class)
                    Integer withAbortOn();

                    class Numbers implements Classifier<Number> {
                        @Override
                        public Outcome classify(Number result, Throwable failure) {
                            return Outcome.SUCCESS;
                        }
                    }

                    abstract class Abstract implements Classifier<Integer> {
                    }

                    final class NoConstructor implements Classifier<Integer> {
                        private NoConstructor() {
                        }

                        @Override
                        public Outcome classify(Integer result, Throwable failure) {
                            return Outcome.SUCCESS;
                        }
                    }

                    final class Generic<T> implements Classifier<T> {
                        @Override
                        public Outcome classify(T result, Throwable failure) {
                            return Outcome.SUCCESS;
                        }
                    }

                    @SuppressWarnings("rawtypes")
                    final class Raw implements Classifier {
                        @Override
                        public Outcome classify(Object result, Throwable failure) {
                            return Outcome.SUCCESS;
                        }
                    }
                }
                """);

        assertThat(result.errorCodes()).containsExactlyInAnyOrder(
                "R4C-CLS-008", "R4C-CLS-010", "R4C-CLS-010", "R4C-CLS-003", "R4C-CLS-007", "R4C-CLS-004", "R4C-CLS-009", "R4C-CLS-011", "R4C-CLS-011");
        assertThat(result.errors()).hasSize(9)
                .anySatisfy(e -> assertThat(e).contains("@Retry.classifier = java.lang.String.class: String does not implement"
                        + " dev.resilience4cata.Classifier<T>."))
                .anySatisfy(e -> assertThat(e).contains("Numbers implements Classifier<java.lang.Number>, but wrongType"
                        + " returns java.lang.String. Implement Classifier<java.lang.String> or a supertype of it."))
                .anySatisfy(e -> assertThat(e).contains("but wrongAsyncType completes with java.lang.String"))
                .anySatisfy(e -> assertThat(e).contains("the classifier must not be abstract"))
                .anySatisfy(e -> assertThat(e).contains("the classifier needs a public no-argument constructor"))
                .anySatisfy(e -> assertThat(e).contains("generic classifier classes are not supported"))
                .anySatisfy(e -> assertThat(e).contains("implements the raw type Classifier. Implement"
                        + " Classifier<java.lang.Integer> instead."))
                .anySatisfy(e -> assertThat(e).contains("@Retry.retryOn cannot be combined with @Retry.classifier"))
                .anySatisfy(e -> assertThat(e).contains("@CircuitBreaker.recordOn cannot be combined with"
                        + " @CircuitBreaker.classifier"));
    }

    @Test
    void rejectsANonStaticNestedClassifier() {
        Compilation result = Compilation.compile(workDir, Map.of("demo.Client", """
                package demo;
                import dev.resilience4cata.annotation.Retry;
                public interface Client {
                    @Retry(classifier = other.Holder.Inner.class)
                    String inner();
                }
                """, "other.Holder", """
                package other;
                import dev.resilience4cata.Classifier;
                public class Holder {
                    public final class Inner implements Classifier<Object> {
                        @Override
                        public Outcome classify(Object result, Throwable failure) {
                            return Outcome.SUCCESS;
                        }
                    }
                }
                """));

        assertThat(result.errorCodes()).containsExactlyInAnyOrder("R4C-CLS-006");
        assertThat(result.errors()).singleElement().asString().contains("a nested classifier class must be static");
    }

    @Test
    void rejectsComposedAnnotationsWithoutResiliencePolicy() {
        Compilation result = compile("demo.Bundle", """
                package demo;
                import dev.resilience4cata.annotation.Retry;
                @Retry(maxAttempts = 2)
                public @interface Bundle {}
                """);

        assertThat(result.errorCodes()).containsExactlyInAnyOrder("R4C-CMP-001");
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

        assertThat(result.errorCodes()).containsExactlyInAnyOrder("R4C-CMP-002");
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

        assertThat(result.errorCodes()).containsExactlyInAnyOrder("R4C-ATR-003", "R4C-ATR-005", "R4C-ATR-001");
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

        assertThat(result.errorCodes()).containsExactlyInAnyOrder("R4C-ATR-002", "R4C-ATR-001");
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

        assertThat(result.errorCodes()).containsExactlyInAnyOrder("R4C-ATR-003", "R4C-ATR-001");
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

        assertThat(result.errorCodes()).containsExactlyInAnyOrder("R4C-ATR-003", "R4C-ATR-002", "R4C-ATR-001");
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
    void passesTheSiblingExceptionTypesWhenNoneTakesEveryFailure() {
        Compilation result = compile("demo.Client", """
                package demo;
                import dev.resilience4cata.annotation.Fallback;
                public interface Client {
                    @Fallback(method = "recover")
                    String find(String id) throws java.io.IOException;
                    @Fallback(method = "anything")
                    String other(String id);

                    default String recover(String id, java.io.IOException e) { return "io"; }
                    static String recover(String id, IllegalStateException e) { return "state"; }
                    default String anything(String id, Throwable e) { return "any"; }
                }
                """);

        assertThat(result.errors()).isEmpty();
        assertThat(result.generatedSource("demo.Client$Resilient"))
                .contains("this.fallback0 = registry.fallbackPolicy(\"demo.Client#find(java.lang.String)\","
                        + " java.util.List.of(java.lang.Throwable.class), java.util.List.of(),"
                        + " java.util.List.of(java.lang.IllegalStateException.class, java.io.IOException.class));")
                .contains("this.fallback1 = registry.fallbackPolicy(\"demo.Client#other(java.lang.String)\","
                        + " java.util.List.of(java.lang.Throwable.class), java.util.List.of());");
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

        assertThat(result.errorCodes()).containsExactlyInAnyOrder(
                "R4C-FB-001", "R4C-FB-001", "R4C-FB-013", "R4C-FB-010", "R4C-FB-003", "R4C-FB-014");
        assertThat(result.errors()).hasSize(6)
                .anySatisfy(e -> assertThat(e).contains("@Fallback needs exactly one of"))
                .anySatisfy(e -> assertThat(e).contains("implements FallbackHandler<java.lang.String>, but wrongType returns int."
                        + " Implement FallbackHandler<java.lang.Integer>"))
                .anySatisfy(e -> assertThat(e).contains("needs a public no-argument constructor"))
                .anySatisfy(e -> assertThat(e).contains("no usable method recover")
                        .contains("recover(java.lang.String) is private")
                        .contains("returns java.lang.Object, which is not assignable to java.lang.String")
                        .contains("throws java.lang.Exception, which find does not declare"))
                .anySatisfy(e -> assertThat(e).contains("@Fallback(demo.Client.Text.class): Text implements"
                        + " FallbackHandler<java.lang.String>, but the generic method generic returns T, which depends on its"
                        + " type parameters"));
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

        assertThat(result.errorCodes()).containsExactlyInAnyOrder("R4C-FB-006", "R4C-FB-009");
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
                    CompletionStage<? super Number> wildcard();
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

        assertThat(result.errorCodes()).containsExactlyInAnyOrder(
                "R4C-ASY-001", "R4C-ASY-001", "R4C-FB-003", "R4C-FB-013");
        assertThat(result.errors()).hasSize(4)
                .anySatisfy(e -> assertThat(e).contains("must return CompletionStage<T> or CompletableFuture<T>")
                        .contains("not ? super X")
                        .contains("returns java.util.concurrent.CompletionStage<? super java.lang.Number>"))
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
