package dev.resilience4cata.processor;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Async methods that return {@code CompletionStage<? extends X>}, {@code CompletableFuture<?>} and the like. */
class WildcardAsyncProcessorTest {

    @TempDir
    Path workDir;

    private Compilation compile(String qualifiedName, String source) {
        return Compilation.compile(workDir, Map.of(qualifiedName, source));
    }

    @Test
    void widensTheDelegateStageToTheUpperBound() {
        Compilation result = compile("demo.Client", """
                package demo;
                import dev.resilience4cata.Classifier;
                import dev.resilience4cata.FallbackContext;
                import dev.resilience4cata.annotation.CircuitBreaker;
                import dev.resilience4cata.annotation.Fallback;
                import dev.resilience4cata.annotation.Retry;
                import dev.resilience4cata.annotation.Timeout;
                import java.util.concurrent.CompletableFuture;
                import java.util.concurrent.CompletionStage;
                public interface Client {
                    @Retry @Fallback(method = "zero")
                    CompletionStage<? extends Number> count(String id);
                    default Integer zero(String id) { return 0; }

                    @Retry(classifier = Any.class) @CircuitBreaker @Fallback(method = "later")
                    CompletableFuture<?> any();
                    default CompletionStage<? extends String> later(Throwable e) { return CompletableFuture.completedFuture(""); }

                    @Fallback(Stage.class)
                    CompletionStage<? extends Number> handled();

                    @Timeout("1s") @Fallback(method = "generic")
                    <T> CompletableFuture<? extends T> generic(Class<T> type);
                    default <T> CompletionStage<? extends T> generic(Class<T> type, Exception e) { return null; }

                    @Timeout("1s")
                    CompletionStage<? extends Number> noFallback();

                    CompletionStage<? extends Number> plain();

                    final class Any implements Classifier<Object> {
                        @Override
                        public Outcome classify(Object result, Throwable failure) {
                            return Outcome.SUCCESS;
                        }
                    }

                    final class Stage implements dev.resilience4cata.FallbackHandler<CompletionStage<Integer>> {
                        @Override
                        public CompletionStage<Integer> apply(FallbackContext context) {
                            return CompletableFuture.completedFuture(1);
                        }
                    }
                }
                """);

        assertThat(result.errors()).isEmpty();
        assertThat(result.generatedSource("demo.Client$Resilient"))
                .contains("public java.util.concurrent.CompletionStage<? extends java.lang.Number> count(java.lang.String p0) {")
                .contains("return this.retry0.executeAsync(() -> dev.resilience4cata.generated.AsyncCall.<java.lang.Number>widen("
                        + "this.delegate.count(p0)), this.fallback0, (r4c$e, r4c$attempts) -> this.r4c$recover0(r4c$e,"
                        + " r4c$attempts, p0));")
                .contains("private java.util.concurrent.CompletionStage<java.lang.Number> r4c$recover0(")
                .contains("return java.util.concurrent.CompletableFuture.completedFuture(this.delegate.zero(p0));")
                .contains("public java.util.concurrent.CompletableFuture<?> any() {")
                .contains("return this.retry2.executeAsync(() -> this.circuitBreaker2.executeAsync(()"
                        + " -> dev.resilience4cata.generated.AsyncCall.<java.lang.Object>widen(this.delegate.any())),"
                        + " this.retryClassifier2, this.fallback2,")
                .contains("return dev.resilience4cata.generated.AsyncCall.<java.lang.Object>widen(this.delegate.later(r4c$e));")
                .contains("return dev.resilience4cata.generated.AsyncCall.<java.lang.Number>widen("
                        + "this.fallbackHandler4.apply(r4c$context));")
                .contains("public <T> java.util.concurrent.CompletableFuture<? extends T> generic(java.lang.Class<T> p0) {")
                .contains("this.timeout5.executeAsync(() -> dev.resilience4cata.generated.AsyncCall.<T>widen("
                        + "this.delegate.generic(p0))), (r4c$e, r4c$attempts) -> this.<T>r4c$recover5(")
                .contains("return dev.resilience4cata.generated.AsyncCall.<T>widen(this.delegate.<T>generic(p0, r4c$f0));")
                .contains("return this.timeout7.executeAsync(() -> dev.resilience4cata.generated.AsyncCall"
                        + ".<java.lang.Number>widen(this.delegate.noFallback()));")
                .contains("return this.delegate.plain();");
    }

    @Test
    void checksFallbacksAndClassifiersAgainstTheUpperBound() {
        Compilation result = compile("demo.Client", """
                package demo;
                import dev.resilience4cata.Classifier;
                import dev.resilience4cata.FallbackContext;
                import dev.resilience4cata.annotation.Fallback;
                import dev.resilience4cata.annotation.Retry;
                import java.util.concurrent.CompletionStage;
                public interface Client {
                    @Fallback(method = "text")
                    CompletionStage<? extends Number> count();
                    default String text() { return ""; }

                    @Fallback(method = "stage")
                    CompletionStage<? extends Number> stage();
                    default CompletionStage<? extends Object> stage(Exception e) { return null; }

                    @Retry(classifier = Ints.class)
                    CompletionStage<? extends Number> classified();

                    @Fallback(Text.class)
                    CompletionStage<?> handled();

                    @Retry
                    CompletionStage<? super Integer> contravariant();

                    final class Ints implements Classifier<Integer> {
                        @Override
                        public Outcome classify(Integer result, Throwable failure) {
                            return Outcome.SUCCESS;
                        }
                    }

                    final class Text implements dev.resilience4cata.FallbackHandler<CompletionStage<String>> {
                        @Override
                        public CompletionStage<String> apply(FallbackContext context) {
                            return null;
                        }
                    }
                }
                """);

        assertThat(result.errorCodes()).containsExactlyInAnyOrder(
                "R4C-FB-003", "R4C-FB-003", "R4C-CLS-010", "R4C-ASY-001");
        assertThat(result.errors()).hasSize(4)
                .anySatisfy(e -> assertThat(e).contains("text() returns java.lang.String, which is neither java.lang.Number"
                        + " nor a CompletionStage of it"))
                .anySatisfy(e -> assertThat(e).contains("stage(java.lang.Exception) returns"
                        + " java.util.concurrent.CompletionStage<? extends java.lang.Object>, which is neither java.lang.Number nor a CompletionStage of it"))
                .anySatisfy(e -> assertThat(e).contains("Ints implements Classifier<java.lang.Integer>, but classified"
                        + " completes with java.lang.Number"))
                .anySatisfy(e -> assertThat(e).contains("not ? super X")
                        .contains("returns java.util.concurrent.CompletionStage<? super java.lang.Integer>"));
    }
}
