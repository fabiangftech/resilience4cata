package dev.resilience4cata.examples;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import dev.resilience4cata.CircuitBreakerHandle;
import dev.resilience4cata.CircuitBreakerState;
import dev.resilience4cata.Pipeline;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import dev.resilience4cata.examples.HalfOpenProbeClient.Buggy;
import dev.resilience4cata.testkit.ManualClock;
import dev.resilience4cata.testkit.ResilienceTesting;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Mono;

/**
 * A classifier that throws while its breaker is half-open must not lose the probe's permit (Resilience4j issue
 * #2324: a throwing predicate left the breaker half-open and rejecting for good). The probe counts as a failure, so
 * the breaker opens again, and once the classifier behaves a probe closes it. Checked for every generated layer that
 * classifies (alone, inside a retry, sharing the retry's classifier, async, {@code Mono}, subclass style) and for a
 * {@link Pipeline}, with the classifier throwing on a result and on an exception.
 */
class HalfOpenProbeEndToEndTest {

    private static final String CLIENT = "dev.resilience4cata.examples.HalfOpenProbeClient#";
    private static final String SERVICE = "dev.resilience4cata.examples.HalfOpenProbeService#";

    /** One way to make a protected call: answers {@code "ok"} or fails with {@code IOException} when {@code fail}. */
    @FunctionalInterface
    interface Shape {
        String call(Fixture fixture, boolean fail) throws Exception;
    }

    /** One registry on a manual clock and every protected object, built per test. */
    static final class Fixture {
        final ManualClock clock = new ManualClock();
        final ResilienceRegistry registry = ResilienceTesting.registry(clock).build();
        final HalfOpenProbeClient client = Resilient.of(HalfOpenProbeClient.class, new HalfOpenProbeClient() {
            @Override
            public String breaker(boolean fail) throws IOException {
                return HalfOpenProbeService.answer(fail);
            }

            @Override
            public String insideRetry(boolean fail) throws IOException {
                return HalfOpenProbeService.answer(fail);
            }

            @Override
            public String sharedClassifier(boolean fail) throws IOException {
                return HalfOpenProbeService.answer(fail);
            }

            @Override
            public CompletionStage<String> async(boolean fail) {
                return stage(fail);
            }

            @Override
            public Mono<String> mono(boolean fail) {
                return Mono.fromCompletionStage(() -> stage(fail));
            }
        }, registry);
        final HalfOpenProbeService service = Resilient.create(HalfOpenProbeService.class, registry);
        final Pipeline<String> pipeline = Pipeline.builder(registry, "pipeline-probe", String.class)
                .circuitBreaker(breaker -> breaker.windowSize(2).minimumCalls(2).failureRateThreshold(50)
                        .waitInOpen(Duration.ofSeconds(10)).halfOpenCalls(1))
                .circuitBreakerClassifier(new Buggy())
                .build();

        private static CompletionStage<String> stage(boolean fail) {
            return fail ? CompletableFuture.failedFuture(new IOException("down")) : CompletableFuture.completedFuture("ok");
        }
    }

    static Stream<Arguments> shapes() {
        return Stream.of(
                Arguments.of("breaker alone", CLIENT + "breaker(boolean)", (Shape) (f, fail) -> f.client.breaker(fail)),
                Arguments.of("inside an unclassified retry", CLIENT + "insideRetry(boolean)",
                        (Shape) (f, fail) -> f.client.insideRetry(fail)),
                Arguments.of("sharing the retry's classifier", CLIENT + "sharedClassifier(boolean)",
                        (Shape) (f, fail) -> f.client.sharedClassifier(fail)),
                Arguments.of("async", CLIENT + "async(boolean)",
                        (Shape) (f, fail) -> join(f.client.async(fail).toCompletableFuture())),
                Arguments.of("Mono", CLIENT + "mono(boolean)", (Shape) (f, fail) -> f.client.mono(fail).block()),
                Arguments.of("subclass", SERVICE + "breaker(boolean)", (Shape) (f, fail) -> f.service.breaker(fail)),
                Arguments.of("subclass sharing the retry's classifier", SERVICE + "sharedClassifier(boolean)",
                        (Shape) (f, fail) -> f.service.sharedClassifier(fail)),
                Arguments.of("pipeline", "pipeline-probe",
                        (Shape) (f, fail) -> f.pipeline.execute(() -> HalfOpenProbeService.answer(fail))));
    }

    @AfterEach
    void mendTheClassifier() {
        Buggy.BROKEN.set(false);
    }

    @ParameterizedTest(name = "{0}, on a result")
    @MethodSource("shapes")
    void aClassifierThatThrowsOnTheProbesResultReopensTheBreaker(String name, String id, Shape shape) throws Exception {
        probe(id, shape, false);
    }

    @ParameterizedTest(name = "{0}, on an exception")
    @MethodSource("shapes")
    void aClassifierThatThrowsOnTheProbesExceptionReopensTheBreaker(String name, String id, Shape shape)
            throws Exception {
        probe(id, shape, true);
    }

    private static void probe(String id, Shape shape, boolean probeFails) throws Exception {
        Fixture fixture = new Fixture();
        for (int i = 0; i < 2; i++) {
            assertThat(catchThrowable(() -> shape.call(fixture, true))).isNotNull();
        }
        CircuitBreakerHandle breaker = fixture.registry.circuitBreaker(id);
        assertThat(breaker.state()).isEqualTo(CircuitBreakerState.OPEN);

        ResilienceTesting.elapseWaitInOpen(breaker, fixture.clock);
        Buggy.BROKEN.set(true);
        Throwable thrown = catchThrowable(() -> shape.call(fixture, probeFails));
        assertThat(unwrap(thrown)).isInstanceOf(IllegalStateException.class).hasMessage("classifier bug");
        // The probe was recorded as a failure: open again, not half-open with its only permit gone.
        assertThat(breaker.state()).isEqualTo(CircuitBreakerState.OPEN);

        Buggy.BROKEN.set(false);
        ResilienceTesting.elapseWaitInOpen(breaker, fixture.clock);
        assertThat(shape.call(fixture, false)).isEqualTo("ok");
        assertThat(breaker.state()).isEqualTo(CircuitBreakerState.CLOSED);
    }

    private static String join(CompletableFuture<String> future) throws Exception {
        try {
            return future.get();
        } catch (ExecutionException e) {
            throw (Exception) e.getCause();
        }
    }

    private static Throwable unwrap(Throwable thrown) {
        Throwable t = thrown;
        while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }
}
