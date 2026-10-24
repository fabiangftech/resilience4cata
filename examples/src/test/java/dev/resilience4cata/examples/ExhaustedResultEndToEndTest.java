package dev.resilience4cata.examples;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import dev.resilience4cata.ResultRejectedException;
import dev.resilience4cata.event.RetryEvent;
import dev.resilience4cata.examples.ExhaustedResultClient.Reply;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;

class ExhaustedResultEndToEndTest {

    private final List<RetryEvent> events = new CopyOnWriteArrayList<>();
    private final ResilienceRegistry registry = ResilienceRegistry.builder().listen(RetryEvent.class, events::add).build();

    private final AtomicInteger calls = new AtomicInteger();
    /** The reply of the n-th call (from 1). */
    private volatile IntFunction<Reply> behaviour = n -> new Reply(503, "busy " + n);

    private final ExhaustedResultClient client = Resilient.of(ExhaustedResultClient.class, new ExhaustedResultClient() {
        @Override
        public Reply get(String path) {
            return call();
        }

        @Override
        public Reply returning(String path) {
            return call();
        }

        @Override
        public Reply withSiblings(String path) {
            return call();
        }

        @Override
        public Reply withHandler(String path) {
            return call();
        }

        @Override
        public CompletionStage<Reply> getAsync(String path) {
            return CompletableFuture.completedFuture(call());
        }

        @Override
        public Reply brokenClassifier(String path) {
            return call();
        }

        @Override
        public CompletionStage<Reply> brokenClassifierAsync(String path) {
            return CompletableFuture.completedFuture(call());
        }

        private Reply call() {
            return behaviour.apply(calls.incrementAndGet());
        }
    }, registry);

    @Test
    void throwsTheLastRejectedResultOnceRetriesRunOut() {
        assertThatThrownBy(() -> client.get("/a")).isInstanceOfSatisfying(ResultRejectedException.class, e -> {
            assertThat(e.result()).isEqualTo(new Reply(503, "busy 3"));
            assertThat(e.attempts()).isEqualTo(3);
            assertThat(e.instanceId()).isEqualTo("dev.resilience4cata.examples.ExhaustedResultClient#get(java.lang.String)");
        });
        assertThat(calls).hasValue(3);
        assertThat(events).last().isInstanceOfSatisfying(RetryEvent.GaveUp.class,
                gaveUp -> assertThat(gaveUp.reason()).isEqualTo(RetryEvent.Reason.MAX_ATTEMPTS));
    }

    @Test
    void acceptedResultsAreReturnedAsBefore() throws IOException {
        behaviour = n -> new Reply(n < 3 ? 503 : 200, "reply " + n);
        assertThat(client.get("/a")).isEqualTo(new Reply(200, "reply 3"));
    }

    @Test
    void returningIsStillTheDefault() throws IOException {
        assertThat(client.returning("/a")).isEqualTo(new Reply(503, "busy 3"));
    }

    @Test
    void aRejectedResultGoesToTheMostSpecificSiblingFallback() throws IOException {
        assertThat(client.withSiblings("/a")).isEqualTo(new Reply(200, "rejected 503 after 2"));
    }

    @Test
    void aFallbackHandlerSeesTheRejectedResultAndTheAttempts() throws IOException {
        assertThat(client.withHandler("/a")).isEqualTo(new Reply(503, "stale after 2"));
    }

    @Test
    void asyncRejectedResultsGoToTheFallback() throws Exception {
        assertThat(client.getAsync("/a").toCompletableFuture().get(5, TimeUnit.SECONDS))
                .isEqualTo(new Reply(200, "rejected 503 after 2"));
        assertThat(calls).hasValue(2);
    }

    @Test
    void aThrowingClassifierPublishesGaveUpBeforeTheFallbackRuns() throws IOException {
        behaviour = n -> new Reply(n == 1 ? 503 : 999, "reply " + n);

        assertThat(client.brokenClassifier("/a")).isEqualTo(new Reply(200, "fallback: classifier bug"));
        assertThat(events).hasSize(2);
        assertThat(events.get(0)).isInstanceOf(RetryEvent.Retrying.class);
        assertThat(events.get(1)).isInstanceOfSatisfying(RetryEvent.GaveUp.class, gaveUp -> {
            assertThat(gaveUp.attempts()).isEqualTo(2);
            assertThat(gaveUp.reason()).isEqualTo(RetryEvent.Reason.CLASSIFIER_FAILED);
            assertThat(gaveUp.failure()).isInstanceOf(IllegalStateException.class).hasMessage("classifier bug");
        });
    }

    @Test
    void anAsyncThrowingClassifierPublishesGaveUp() {
        behaviour = n -> new Reply(999, "reply " + n);

        assertThatThrownBy(() -> client.brokenClassifierAsync("/a").toCompletableFuture().get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(IllegalStateException.class);
        assertThat(events).singleElement().isInstanceOfSatisfying(RetryEvent.GaveUp.class, gaveUp -> {
            assertThat(gaveUp.attempts()).isEqualTo(1);
            assertThat(gaveUp.reason()).isEqualTo(RetryEvent.Reason.CLASSIFIER_FAILED);
        });
    }
}
