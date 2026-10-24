package dev.resilience4cata.processor;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code @Retry(onExhaustedResult = ...)} and the {@code GaveUp} event of a throwing classifier. */
class ExhaustedResultProcessorTest {

    @TempDir
    Path workDir;

    private Compilation compile(String source) {
        return Compilation.compile(workDir, Map.of("demo.Client", source));
    }

    private static final String CLASSIFIERS = """
                final class Status implements Classifier<Integer> {
                    @Override
                    public Outcome classify(Integer result, Throwable failure) {
                        return failure != null || result >= 500 ? Outcome.FAILURE : Outcome.SUCCESS;
                    }
                }

                final class Any implements Classifier<Object> {
                    @Override
                    public Outcome classify(Object result, Throwable failure) {
                        return Outcome.FAILURE;
                    }
                }

                final class Zero implements dev.resilience4cata.FallbackHandler<Integer> {
                    @Override
                    public Integer apply(dev.resilience4cata.FallbackContext context) throws java.io.IOException {
                        return 0;
                    }
                }
            }
            """;

    @Test
    void generatesThrowingLayersThatCompileWithoutWarnings() {
        Compilation result = compile("""
                package demo;
                import dev.resilience4cata.Classifier;
                import dev.resilience4cata.annotation.CircuitBreaker;
                import dev.resilience4cata.annotation.ExhaustedResult;
                import dev.resilience4cata.annotation.Fallback;
                import dev.resilience4cata.annotation.Retry;
                import java.util.concurrent.CompletionStage;
                public interface Client {
                    @Retry(classifier = Status.class, onExhaustedResult = ExhaustedResult.THROW)
                    Integer plain(String id) throws java.io.IOException;
                    @Retry(classifier = Status.class, onExhaustedResult = ExhaustedResult.THROW)
                    @Fallback(method = "recover")
                    Integer siblings(String id) throws java.io.IOException;
                    @Retry(classifier = Status.class, onExhaustedResult = ExhaustedResult.THROW)
                    @CircuitBreaker(classifier = Status.class)
                    @Fallback(Zero.class)
                    Integer handler(String id) throws java.io.IOException;
                    @Retry(classifier = Any.class, onExhaustedResult = ExhaustedResult.THROW)
                    @Fallback(method = "nothingFallback")
                    void nothing();
                    @Retry(classifier = Any.class, onExhaustedResult = ExhaustedResult.THROW)
                    int primitive();
                    @Retry(classifier = Status.class, onExhaustedResult = ExhaustedResult.THROW) @Fallback(method = "later")
                    CompletionStage<Integer> async(String id);
                    @Retry(classifier = Status.class, onExhaustedResult = ExhaustedResult.THROW)
                    CompletionStage<Integer> asyncPlain();
                    @Retry(classifier = Status.class, onExhaustedResult = ExhaustedResult.RETURN)
                    Integer returning(String id);

                    default Integer recover(String id, java.io.IOException e) { return -1; }
                    default Integer recover(String id, dev.resilience4cata.ResultRejectedException e) { return -2; }
                    default void nothingFallback() { }
                    default Integer later(String id) { return -3; }

                """ + CLASSIFIERS);

        assertThat(result.errors()).isEmpty();
        assertThat(result.success()).isTrue();
        String generated = result.generatedSource("demo.Client$Resilient");
        assertThat(generated)
                // without a fallback: thrown where the retries give up on the result
                .contains("throw r4c$retry.rejected(r4c$result, r4c$attempts); // rejected, and no retries left")
                // with a fallback: a catch parameter, so that the fallback's rethrow is precise
                .contains("throw r4c$retry.rejected(r4c$result, r4c$attempts);\n"
                        + "                } catch (java.lang.Throwable r4c$r) {\n"
                        + "                    if (!this.fallback1.handles(r4c$r)) {")
                .contains("if (r4c$r instanceof dev.resilience4cata.ResultRejectedException r4c$f0) {")
                .contains("this.fallback2.context(r4c$r, r4c$attempts, new java.lang.Object[] {p0});")
                .contains("throw r4c$retry.rejected(null, r4c$attempts);")
                .contains("this.retry5.executeAsync(() -> this.delegate.async(p0), this.retryClassifier5, true, this.fallback5,")
                .contains("this.retry6.executeAsync(() -> this.delegate.asyncPlain(), this.retryClassifier6, true, null, null)")
                // RETURN is the default: nothing changes
                .contains("return r4c$result; // rejected, but no retries left: the last result");
        assertThat(generated.split("r4c\\$retry\\.rejected\\(", -1)).as("one per synchronous THROW method").hasSize(6);
    }

    @Test
    void aThrowingClassifierPublishesGaveUpWithAndWithoutAFallback() {
        Compilation result = compile("""
                package demo;
                import dev.resilience4cata.Classifier;
                import dev.resilience4cata.annotation.Fallback;
                import dev.resilience4cata.annotation.Retry;
                public interface Client {
                    @Retry(classifier = Status.class)
                    Integer plain(String id) throws java.io.IOException;
                    @Retry(classifier = Status.class)
                    @Fallback(method = "recover")
                    Integer withFallback(String id) throws java.io.IOException;

                    default Integer recover(String id) { return -1; }

                """ + CLASSIFIERS);

        assertThat(result.errors()).isEmpty();
        String generated = result.generatedSource("demo.Client$Resilient");
        assertThat(generated)
                .contains("} catch (java.lang.Throwable r4c$c) {\n"
                        + "                    r4c$retry.onClassifierFailure(r4c$attempts + 1, r4c$c);\n"
                        + "                    throw r4c$c;")
                .contains("} catch (java.lang.Throwable r4c$c) {\n"
                        + "                r4c$retry.onClassifierFailure(r4c$attempts + 1, r4c$c);\n"
                        + "                if (!this.fallback1.handles(r4c$c)) {");
        assertThat(generated.split("onClassifierFailure", -1)).as("two classify calls per method").hasSize(5);
    }

    @Test
    void onExhaustedResultNeedsAClassifier() {
        Compilation result = compile("""
                package demo;
                import dev.resilience4cata.Classifier;
                import dev.resilience4cata.annotation.CircuitBreaker;
                import dev.resilience4cata.annotation.ExhaustedResult;
                import dev.resilience4cata.annotation.Retry;
                public interface Client {
                    @Retry(onExhaustedResult = ExhaustedResult.THROW)
                    Integer noClassifier();
                    @Retry(onExhaustedResult = ExhaustedResult.RETURN) @CircuitBreaker(classifier = Status.class)
                    Integer breakerClassifierOnly();

                """ + CLASSIFIERS);

        assertThat(result.errors()).hasSize(2).allSatisfy(e -> assertThat(e).contains(
                "@Retry.onExhaustedResult needs @Retry.classifier: only a classifier can reject a result."));
    }

    @Test
    void mergesOnExhaustedResultLikeOtherAttributes() {
        Compilation result = compile("""
                package demo;
                import dev.resilience4cata.Classifier;
                import dev.resilience4cata.annotation.ExhaustedResult;
                import dev.resilience4cata.annotation.ResiliencePolicy;
                import dev.resilience4cata.annotation.Retry;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                @Retry(classifier = Client.Status.class, onExhaustedResult = ExhaustedResult.THROW)
                public interface Client {
                    Integer inherited();
                    @Retry(onExhaustedResult = ExhaustedResult.RETURN)
                    Integer overridden();
                    @Bundle
                    Integer bundled();

                    @ResiliencePolicy
                    @Retry(classifier = Any.class, onExhaustedResult = ExhaustedResult.RETURN)
                    @Retention(RetentionPolicy.CLASS)
                    @interface Bundle {
                    }

                """ + CLASSIFIERS);

        assertThat(result.errors()).isEmpty();
        String generated = result.generatedSource("demo.Client$Resilient");
        assertThat(generated.split("throw r4c\\$retry\\.rejected\\(", -1)).as("only inherited()").hasSize(2);
    }
}
