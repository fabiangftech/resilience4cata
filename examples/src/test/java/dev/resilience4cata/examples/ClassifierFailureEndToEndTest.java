package dev.resilience4cata.examples;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import dev.resilience4cata.CircuitBreakerSnapshot;
import dev.resilience4cata.Classifier;
import dev.resilience4cata.Pipeline;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import dev.resilience4cata.event.RetryEvent;
import dev.resilience4cata.examples.ClassifierFailureClient.Picky;
import dev.resilience4cata.examples.ClassifierFailureClient.Recover;
import dev.resilience4cata.examples.ClassifierFailureClient.Reply;
import dev.resilience4cata.examples.ClassifierFailureClient.RetryAnyFailure;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A classifier that throws in the breaker layer ends the call at once: the breaker records one failure,
 * the outer retry does not classify the classifier's exception again, publishes one
 * {@code GaveUp(CLASSIFIER_FAILED)}, and the caller (or the fallback) gets that very exception.
 */
class ClassifierFailureEndToEndTest {

    private static final String PREFIX = "dev.resilience4cata.examples.ClassifierFailureClient#";

    private final List<RetryEvent> events = new CopyOnWriteArrayList<>();
    private final ResilienceRegistry registry = ResilienceRegistry.builder().listen(RetryEvent.class, events::add).build();
    private final AtomicInteger calls = new AtomicInteger();

    private final ClassifierFailureClient client = Resilient.of(ClassifierFailureClient.class, new ClassifierFailureClient() {
        @Override
        public Reply shared(String path) {
            return call();
        }

        @Override
        public Reply sharedWithFallback(String path) {
            return call();
        }

        @Override
        public Reply different(String path) {
            return call();
        }

        @Override
        public Reply unclassifiedRetry(String path) {
            return call();
        }

        @Override
        public CompletionStage<Reply> sharedAsync(String path) {
            return CompletableFuture.completedFuture(call());
        }

        @Override
        public CompletionStage<Reply> differentAsync(String path) {
            return CompletableFuture.completedFuture(call());
        }
    }, registry);

    private Reply call() {
        calls.incrementAndGet();
        return new Reply(999, "bug");
    }

    @BeforeEach
    void clearFallbacks() {
        Recover.SEEN.clear();
    }

    @Test
    void aSharedClassifierThatThrowsInTheBreakerEndsTheCall() {
        Throwable thrown = catchThrowable(() -> client.shared("/a"));

        assertThat(thrown).isInstanceOf(IllegalStateException.class).hasMessage("classifier bug");
        assertEndedOnce(thrown, "shared(java.lang.String)");
    }

    @Test
    void theFusedFallbackGetsTheClassifiersException() throws IOException {
        assertThat(client.sharedWithFallback("/a")).isEqualTo(new Reply(200, "fallback after 1: classifier bug"));
        assertThat(Recover.SEEN).singleElement().isInstanceOf(IllegalStateException.class);
        assertEndedOnce(Recover.SEEN.get(0), "sharedWithFallback(java.lang.String)");
    }

    @Test
    void aRetryClassifierOfAnotherClassDoesNotClassifyTheBreakerClassifiersException() {
        Throwable thrown = catchThrowable(() -> client.different("/a"));

        assertThat(thrown).isInstanceOf(IllegalStateException.class).hasMessage("classifier bug");
        assertEndedOnce(thrown, "different(java.lang.String)");
    }

    @Test
    void aRetryWithoutClassifierDoesNotRetryTheBreakerClassifiersException() throws IOException {
        assertThat(client.unclassifiedRetry("/a")).isEqualTo(new Reply(200, "fallback after 1: classifier bug"));
        assertThat(Recover.SEEN).singleElement().isInstanceOf(IllegalStateException.class);
        assertEndedOnce(Recover.SEEN.get(0), "unclassifiedRetry(java.lang.String)");
    }

    @Test
    void asyncASharedClassifierThatThrowsInTheBreakerEndsTheCall() {
        Throwable thrown = catchThrowable(() -> client.sharedAsync("/a").toCompletableFuture().get(5, TimeUnit.SECONDS));

        assertThat(thrown.getCause()).isInstanceOf(IllegalStateException.class).hasMessage("classifier bug");
        assertEndedOnce(thrown.getCause(), "sharedAsync(java.lang.String)");
    }

    @Test
    void asyncTheFallbackGetsTheBreakerClassifiersException() throws Exception {
        assertThat(client.differentAsync("/a").toCompletableFuture().get(5, TimeUnit.SECONDS))
                .isEqualTo(new Reply(200, "fallback after 1: classifier bug"));
        assertThat(Recover.SEEN).singleElement().isInstanceOf(IllegalStateException.class);
        assertEndedOnce(Recover.SEEN.get(0), "differentAsync(java.lang.String)");
    }

    @Test
    void aPipelineEndsTheCallToo() {
        for (Classifier<Reply> retryClassifier : List.of(new Picky(), new RetryAnyFailure())) {
            events.clear();
            calls.set(0);
            String id = "pipeline-" + retryClassifier.getClass().getSimpleName();
            Pipeline<Reply> pipeline = pipeline(id, retryClassifier);

            Throwable thrown = catchThrowable(() -> pipeline.execute(this::call));

            assertThat(thrown).isInstanceOf(IllegalStateException.class).hasMessage("classifier bug");
            assertEndedOnce(thrown, id, id);
        }
    }

    @Test
    void anAsyncPipelineEndsTheCallToo() {
        for (Classifier<Reply> retryClassifier : List.of(new Picky(), new RetryAnyFailure())) {
            events.clear();
            calls.set(0);
            String id = "pipeline-async-" + retryClassifier.getClass().getSimpleName();
            Pipeline<Reply> pipeline = pipeline(id, retryClassifier);

            Throwable thrown = catchThrowable(() -> pipeline.executeAsync(() -> CompletableFuture.completedFuture(call())).join());

            assertThat(thrown).isInstanceOf(CompletionException.class);
            assertThat(thrown.getCause()).isInstanceOf(IllegalStateException.class).hasMessage("classifier bug");
            assertEndedOnce(thrown.getCause(), id, id);
        }
    }

    @Test
    void aPipelineWithAFallbackGivesItTheClassifiersException() throws Exception {
        List<Throwable> seen = new CopyOnWriteArrayList<>();
        Pipeline<Reply> pipeline = Pipeline.builder(registry, "pipeline-fallback", Reply.class)
                .retry(r -> r.maxAttempts(3).delay(Duration.ZERO))
                .circuitBreaker(cb -> cb.windowSize(10).minimumCalls(10))
                .circuitBreakerClassifier(new Picky())
                .fallbackHandler(context -> {
                    seen.add(context.failure());
                    return new Reply(200, "fallback after " + context.attempts());
                })
                .build();

        assertThat(pipeline.execute(this::call)).isEqualTo(new Reply(200, "fallback after 1"));
        assertThat(pipeline.executeAsync(() -> CompletableFuture.completedFuture(call())).get(5, TimeUnit.SECONDS))
                .isEqualTo(new Reply(200, "fallback after 1"));
        assertThat(seen).hasSize(2).allSatisfy(failure -> assertThat(failure).isInstanceOf(IllegalStateException.class));
        assertThat(calls).hasValue(2);
        assertThat(events).hasSize(2).allSatisfy(event -> assertThat(event).isInstanceOfSatisfying(RetryEvent.GaveUp.class,
                gaveUp -> assertThat(gaveUp.reason()).isEqualTo(RetryEvent.Reason.CLASSIFIER_FAILED)));
        assertThat(events.get(0)).isInstanceOfSatisfying(RetryEvent.GaveUp.class, gaveUp -> assertThat(gaveUp.failure()).isSameAs(seen.get(0)));
        assertThat(events.get(1)).isInstanceOfSatisfying(RetryEvent.GaveUp.class, gaveUp -> assertThat(gaveUp.failure()).isSameAs(seen.get(1)));
    }

    private Pipeline<Reply> pipeline(String id, Classifier<Reply> retryClassifier) {
        return Pipeline.builder(registry, id, Reply.class)
                .retry(r -> r.maxAttempts(3).delay(Duration.ZERO))
                .circuitBreaker(cb -> cb.windowSize(10).minimumCalls(10))
                .retryClassifier(retryClassifier)
                .circuitBreakerClassifier(new Picky())
                .build();
    }

    private void assertEndedOnce(Throwable failure, String method) {
        assertEndedOnce(failure, PREFIX + method, PREFIX + method);
    }

    /** One call, one failure recorded by the breaker, one GaveUp(CLASSIFIER_FAILED) with the same exception. */
    private void assertEndedOnce(Throwable failure, String retryId, String breakerId) {
        assertThat(calls).hasValue(1);
        CircuitBreakerSnapshot breaker = registry.circuitBreaker(breakerId).snapshot();
        assertThat(breaker.bufferedCalls()).isEqualTo(1);
        assertThat(breaker.failedCalls()).isEqualTo(1);
        assertThat(events).singleElement().isInstanceOfSatisfying(RetryEvent.GaveUp.class, gaveUp -> {
            assertThat(gaveUp.instance()).isEqualTo(retryId);
            assertThat(gaveUp.attempts()).isEqualTo(1);
            assertThat(gaveUp.reason()).isEqualTo(RetryEvent.Reason.CLASSIFIER_FAILED);
            assertThat(gaveUp.failure()).isSameAs(failure);
        });
    }
}
