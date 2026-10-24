package dev.resilience4cata.examples;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.Pipeline;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import dev.resilience4cata.ResultRejectedException;
import dev.resilience4cata.event.RetryEvent;
import dev.resilience4cata.examples.ExhaustedResultClient.Reply;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * A pipeline with {@code onExhaustedResult(THROW)}, a void call and an asynchronous fallback behaves like
 * the generated code of {@link ExhaustedResultClient} for the same configuration.
 */
class PipelineExhaustedResultTest {

    private final List<RetryEvent> events = new CopyOnWriteArrayList<>();
    private final ResilienceRegistry registry = ResilienceRegistry.builder().listen(RetryEvent.class, events::add).build();

    private static Reply rejected(Throwable failure) {
        return failure instanceof ResultRejectedException e && e.result() instanceof Reply last
                ? new Reply(200, "rejected " + last.status() + " after " + e.attempts())
                : new Reply(200, "fallback: " + failure.getMessage());
    }

    private Pipeline<Reply> pipeline(String id) {
        return Pipeline.builder(registry, id, Reply.class)
                .retry(r -> r.maxAttempts(2).delay(Duration.ZERO))
                .classifier(new ExhaustedResultClient.Status())
                .onExhaustedResult(Pipeline.ExhaustedResult.THROW)
                .fallback(PipelineExhaustedResultTest::rejected)
                .fallbackAsync(failure -> CompletableFuture.supplyAsync(() -> rejected(failure)))
                .build();
    }

    @Test
    void behavesLikeTheAnnotatedMethod() throws Exception {
        ExhaustedResultClient annotated = Resilient.of(ExhaustedResultClient.class, new Busy(), registry);
        Pipeline<Reply> pipeline = pipeline("pipeline-exhausted");

        assertThat(pipeline.execute(() -> new Reply(503, "busy"))).isEqualTo(annotated.withSiblings("/a"));
        assertThat(pipeline.executeAsync(() -> CompletableFuture.completedFuture(new Reply(503, "busy"))).join())
                .isEqualTo(annotated.getAsync("/a").toCompletableFuture().join());
    }

    @Test
    void aClassifierFailurePublishesTheSameGaveUpAsTheAnnotatedMethod() throws Exception {
        ExhaustedResultClient annotated = Resilient.of(ExhaustedResultClient.class, new Busy() {
            @Override
            public Reply brokenClassifier(String path) {
                return new Reply(999, "bug");
            }
        }, registry);
        Pipeline<Reply> pipeline = pipeline("pipeline-broken");

        assertThat(pipeline.execute(() -> new Reply(999, "bug"))).isEqualTo(annotated.brokenClassifier("/a"));
        assertThat(events).hasSize(2).allSatisfy(event -> assertThat(event).isInstanceOfSatisfying(RetryEvent.GaveUp.class,
                gaveUp -> {
                    assertThat(gaveUp.reason()).isEqualTo(RetryEvent.Reason.CLASSIFIER_FAILED);
                    assertThat(gaveUp.attempts()).isEqualTo(1);
                }));
    }

    @Test
    void runProtectsAVoidCall() {
        AtomicInteger sent = new AtomicInteger();
        List<Throwable> fallbacks = new ArrayList<>();
        Pipeline<Void> audit = Pipeline.builder(registry, "pipeline-run", Void.class)
                .retry(r -> r.maxAttempts(3).delay(Duration.ZERO))
                .fallback(failure -> {
                    fallbacks.add(failure);
                    return null;
                })
                .build();

        audit.run(() -> {
            if (sent.incrementAndGet() < 3) {
                throw new IllegalStateException("down");
            }
        });
        assertThat(sent).hasValue(3);
        assertThat(fallbacks).isEmpty();

        audit.run(() -> {
            throw new IllegalStateException("still down");
        });
        assertThat(fallbacks).singleElement().extracting(Throwable::getMessage).isEqualTo("still down");
    }

    /** Every call returns 503. */
    private static class Busy implements ExhaustedResultClient {
        @Override
        public Reply get(String path) {
            return new Reply(503, "busy");
        }

        @Override
        public Reply returning(String path) {
            return get(path);
        }

        @Override
        public Reply withSiblings(String path) {
            return get(path);
        }

        @Override
        public Reply withHandler(String path) {
            return get(path);
        }

        @Override
        public CompletionStage<Reply> getAsync(String path) {
            return CompletableFuture.completedFuture(get(path));
        }

        @Override
        public Reply brokenClassifier(String path) {
            return get(path);
        }

        @Override
        public CompletionStage<Reply> brokenClassifierAsync(String path) {
            return getAsync(path);
        }
    }
}
