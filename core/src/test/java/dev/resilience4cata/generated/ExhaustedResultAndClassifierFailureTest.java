package dev.resilience4cata.generated;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.ResultRejectedException;
import dev.resilience4cata.RetryConfig;
import dev.resilience4cata.event.EventListener;
import dev.resilience4cata.event.ResilienceEvent;
import dev.resilience4cata.event.RetryEvent;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** {@code onExhaustedResult = THROW} and the {@code GaveUp} event of a classifier that throws, in {@link RetryPolicy}. */
class ExhaustedResultAndClassifierFailureTest {

    private final AtomicLong now = new AtomicLong(1_000_000_000L);
    private final List<ResilienceEvent> events = new CopyOnWriteArrayList<>();

    /** 5xx is a failure; I/O errors are failures, other exceptions are ignored. */
    private static final Classifier<Integer> STATUS = (result, failure) -> {
        if (failure != null) {
            return failure instanceof IOException ? Classifier.Outcome.FAILURE : Classifier.Outcome.IGNORE;
        }
        return result >= 500 ? Classifier.Outcome.FAILURE : Classifier.Outcome.SUCCESS;
    };

    private static final IllegalStateException BROKEN = new IllegalStateException("classifier bug");

    private static final Classifier<Integer> THROWING = (result, failure) -> {
        throw BROKEN;
    };

    private RetryPolicy listened(RetryConfig.Builder config) {
        PolicyEnvironment environment = PolicyEnvironment.forTest(now::get, now::addAndGet,
                EventDispatcher.of(List.of(new EventDispatcher.Subscription(RetryEvent.class, null,
                        (EventListener<ResilienceEvent>) events::add))));
        return new RetryPolicy("retry", config.delay(Duration.ZERO).jitter(RetryConfig.Jitter.NONE).build(), environment);
    }

    @Test
    void rejectedCarriesTheResultTheAttemptsAndTheId() {
        RetryPolicy retry = listened(RetryConfig.builder());
        ResultRejectedException rejected = retry.rejected(503, 3);

        assertThat(rejected.result()).isEqualTo(503);
        assertThat(rejected.result(Integer.class)).isEqualTo(503);
        assertThat(rejected.attempts()).isEqualTo(3);
        assertThat(rejected.instanceId()).isEqualTo("retry");
        assertThat(rejected).hasMessage("Retry 'retry' gave up after 3 attempts: the classifier rejected the last result");
        assertThat(rejected.getStackTrace()).isEmpty();
        assertThat(retry.rejected(null, 1)).hasMessageContaining("after 1 attempt:");
    }

    @Test
    void aClassifierFailurePublishesGaveUp() {
        RetryPolicy retry = listened(RetryConfig.builder());
        retry.onClassifierFailure(2, BROKEN);

        assertThat(events).containsExactly(
                new RetryEvent.GaveUp("retry", now.get(), 2, RetryEvent.Reason.CLASSIFIER_FAILED, BROKEN));
    }

    @Test
    void aDisabledRetryPublishesNothingForAClassifierFailure() {
        listened(RetryConfig.builder().enabled(false)).onClassifierFailure(1, BROKEN);
        assertThat(events).isEmpty();
    }

    @Test
    void withoutListenersAClassifierFailureIsANoOp() {
        new RetryPolicy("quiet", RetryConfig.DEFAULTS, PolicyEnvironment.DEFAULT).onClassifierFailure(1, BROKEN);
        assertThat(events).isEmpty();
    }

    @Test
    void asyncThrowOnExhaustedResultFailsWithTheLastResult() {
        RetryPolicy retry = listened(RetryConfig.builder().maxAttempts(3));
        AtomicInteger calls = new AtomicInteger();

        CompletableFuture<Integer> exhausted = retry.executeAsync(
                () -> CompletableFuture.completedFuture(500 + calls.incrementAndGet()), STATUS, true, null, null);

        assertThatThrownBy(() -> exhausted.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                .cause().isInstanceOfSatisfying(ResultRejectedException.class, e -> {
                    assertThat(e.result()).isEqualTo(503);
                    assertThat(e.attempts()).isEqualTo(3);
                });
        assertThat(events).last().isEqualTo(
                new RetryEvent.GaveUp("retry", now.get(), 3, RetryEvent.Reason.MAX_ATTEMPTS, null));
    }

    @Test
    void asyncThrowOnExhaustedResultStillReturnsAcceptedResults() throws Exception {
        RetryPolicy retry = listened(RetryConfig.builder().maxAttempts(3));
        AtomicInteger calls = new AtomicInteger();

        CompletableFuture<Integer> recovered = retry.executeAsync(
                () -> CompletableFuture.completedFuture(calls.incrementAndGet() < 3 ? 503 : 200), STATUS, true, null, null);

        assertThat(recovered.get(5, TimeUnit.SECONDS)).isEqualTo(200);
        assertThat(calls).hasValue(3);
    }

    @Test
    void asyncThrowOnExhaustedResultGoesThroughTheFallback() throws Exception {
        RetryPolicy retry = listened(RetryConfig.builder().maxAttempts(2));
        FallbackPolicy fallback = new FallbackPolicy("test", List.of(Throwable.class), List.of());
        List<Object> seen = new ArrayList<>();

        CompletableFuture<Integer> result = retry.executeAsync(() -> CompletableFuture.completedFuture(503), STATUS, true,
                fallback, (failure, attempts) -> {
                    seen.add(((ResultRejectedException) failure).result());
                    seen.add(attempts);
                    return CompletableFuture.completedFuture(-1);
                });

        assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo(-1);
        assertThat(seen).containsExactly(503, 2);
    }

    @Test
    void asyncReturnOnExhaustedResultIsTheDefault() throws Exception {
        RetryPolicy retry = listened(RetryConfig.builder().maxAttempts(2));
        assertThat(retry.executeAsync(() -> CompletableFuture.completedFuture(503), STATUS, false, null, null)
                .get(5, TimeUnit.SECONDS)).isEqualTo(503);
        assertThat(retry.executeAsync(() -> CompletableFuture.completedFuture(503), STATUS, null, null)
                .get(5, TimeUnit.SECONDS)).isEqualTo(503);
    }

    @Test
    void anAsyncClassifierThatThrowsPublishesGaveUpForResultsAndFailures() {
        RetryPolicy retry = listened(RetryConfig.builder().maxAttempts(3));

        CompletableFuture<Integer> onResult = retry.executeAsync(() -> CompletableFuture.completedFuture(1), THROWING,
                null, null);
        assertThatThrownBy(() -> onResult.get(5, TimeUnit.SECONDS)).hasCause(BROKEN);
        CompletableFuture<Integer> onFailure = retry.executeAsync(
                () -> CompletableFuture.failedFuture(new IOException("down")), THROWING, null, null);
        assertThatThrownBy(() -> onFailure.get(5, TimeUnit.SECONDS)).hasCause(BROKEN);

        RetryEvent.GaveUp expected = new RetryEvent.GaveUp("retry", now.get(), 1, RetryEvent.Reason.CLASSIFIER_FAILED, BROKEN);
        assertThat(events).containsExactly(expected, expected);
    }

    @Test
    void anAsyncClassifierThatThrowsAfterARetryCountsTheAttempt() {
        RetryPolicy retry = listened(RetryConfig.builder().maxAttempts(5));
        AtomicInteger calls = new AtomicInteger();
        Classifier<Integer> second = (result, failure) -> {
            if (calls.get() == 2) {
                throw BROKEN;
            }
            return Classifier.Outcome.FAILURE;
        };

        CompletableFuture<Integer> result = retry.executeAsync(
                () -> CompletableFuture.completedFuture(calls.incrementAndGet()), second, null, null);

        assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS)).hasCause(BROKEN);
        assertThat(events).hasSize(2).last().isEqualTo(
                new RetryEvent.GaveUp("retry", now.get(), 2, RetryEvent.Reason.CLASSIFIER_FAILED, BROKEN));
    }

    @Test
    void anAsyncBreakerInsideARetryWrapsItsClassifiersException() {
        CircuitBreakerPolicy breaker = new CircuitBreakerPolicy("cb", dev.resilience4cata.CircuitBreakerConfig.DEFAULTS,
                PolicyEnvironment.DEFAULT);

        CompletableFuture<Integer> alone = breaker.executeAsync(() -> CompletableFuture.completedFuture(1), THROWING, false);
        assertThatThrownBy(() -> alone.get(5, TimeUnit.SECONDS)).hasCause(BROKEN);
        CompletableFuture<Integer> inside = breaker.executeAsync(() -> CompletableFuture.completedFuture(1), THROWING, true);
        assertThatThrownBy(() -> inside.get(5, TimeUnit.SECONDS)).cause()
                .isInstanceOf(ClassifierFailure.class).hasCause(BROKEN);
        assertThat(breaker.snapshot().failedCalls()).isEqualTo(2);
    }

    @Test
    void anAsyncRetryEndsTheCallWithAWrappedClassifierException() {
        RetryPolicy retry = listened(RetryConfig.builder().maxAttempts(3));
        AtomicInteger calls = new AtomicInteger();
        Classifier<Integer> retryEverything = (result, failure) -> Classifier.Outcome.FAILURE;

        CompletableFuture<Integer> unclassified = retry.executeAsync(() -> {
            calls.incrementAndGet();
            return CompletableFuture.failedFuture(new ClassifierFailure(BROKEN));
        }, null, null);
        assertThatThrownBy(() -> unclassified.get(5, TimeUnit.SECONDS)).cause().isSameAs(BROKEN);
        CompletableFuture<Integer> classified = retry.executeAsync(() -> {
            calls.incrementAndGet();
            return CompletableFuture.failedFuture(new ClassifierFailure(BROKEN));
        }, retryEverything, null, null);
        assertThatThrownBy(() -> classified.get(5, TimeUnit.SECONDS)).cause().isSameAs(BROKEN);

        assertThat(calls).hasValue(2);
        RetryEvent.GaveUp expected = new RetryEvent.GaveUp("retry", now.get(), 1, RetryEvent.Reason.CLASSIFIER_FAILED, BROKEN);
        assertThat(events).containsExactly(expected, expected);
    }

    @Test
    void aClassifierFailureHandsBackTheClassifiersException() {
        ClassifierFailure wrapped = new ClassifierFailure(BROKEN);
        assertThat(wrapped.failure()).isSameAs(BROKEN);
        assertThat(wrapped.getStackTrace()).isEmpty();

        AssertionError error = new AssertionError("bug");
        assertThatThrownBy(() -> new ClassifierFailure(error).failure()).isSameAs(error);
        IOException checked = new IOException("sneaky");
        assertThatThrownBy(() -> new ClassifierFailure(checked).failure()).isSameAs(checked);
    }
}
