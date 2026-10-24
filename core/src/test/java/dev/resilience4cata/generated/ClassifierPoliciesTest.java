package dev.resilience4cata.generated;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.resilience4cata.CallNotPermittedException;
import dev.resilience4cata.CircuitBreakerConfig;
import dev.resilience4cata.CircuitBreakerState;
import dev.resilience4cata.Classifier;
import dev.resilience4cata.Classifier.Outcome;
import dev.resilience4cata.RetryConfig;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The outcome methods that generated code calls for methods with a {@link Classifier}. */
class ClassifierPoliciesTest {

    private final AtomicLong now = new AtomicLong(1_000_000);
    private final List<Long> sleeps = new ArrayList<>();

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    private RetryPolicy retry(RetryConfig.Builder builder) {
        return new RetryPolicy(builder.jitter(RetryConfig.Jitter.NONE).build(), now::get, sleeps::add);
    }

    private CircuitBreakerPolicy breaker(CircuitBreakerConfig.Builder config) {
        return new CircuitBreakerPolicy("test", config.build(), now::get);
    }

    private static CircuitBreakerConfig.Builder window(int size) {
        return CircuitBreakerConfig.builder().windowSize(size).minimumCalls(size)
                .failureRateThreshold(50).waitInOpen(Duration.ofSeconds(10)).halfOpenCalls(1);
    }

    // --- Retry, results

    @Test
    void rejectedResultsAreRetriedUntilMaxAttempts() {
        RetryPolicy retry = retry(RetryConfig.builder().maxAttempts(3).delay(Duration.ofMillis(5)));

        assertThat(retry.onFailedResult(1, retry.start())).isEqualTo(Duration.ofMillis(5).toNanos());
        assertThat(retry.onFailedResult(2, retry.start())).isEqualTo(Duration.ofMillis(10).toNanos());
        assertThat(retry.onFailedResult(3, retry.start())).isEqualTo(RetryPolicy.NO_RETRY);
    }

    @Test
    void rejectedResultsRespectMaxDurationEnabledAndInterrupts() {
        RetryPolicy bounded = retry(RetryConfig.builder().maxAttempts(10).delay(Duration.ofMillis(100))
                .backoff(RetryConfig.Backoff.FIXED).maxDuration(Duration.ofMillis(250)));
        long start = bounded.start();
        assertThat(bounded.onFailedResult(1, start)).isNotNegative();
        now.addAndGet(Duration.ofMillis(200).toNanos());
        assertThat(bounded.onFailedResult(2, start)).isEqualTo(RetryPolicy.NO_RETRY);

        assertThat(retry(RetryConfig.builder().enabled(false)).onFailedResult(1, 0)).isEqualTo(RetryPolicy.NO_RETRY);

        RetryPolicy retry = retry(RetryConfig.builder());
        Thread.currentThread().interrupt();
        assertThat(retry.onFailedResult(1, 0)).isEqualTo(RetryPolicy.NO_RETRY);
    }

    // --- Retry, exceptions

    @Test
    void onlyAFailureVerdictIsRetried() {
        RetryPolicy retry = retry(RetryConfig.builder());
        IOException failure = new IOException();

        assertThat(retry.onFailure(1, failure, 0, Outcome.FAILURE)).isNotNegative();
        assertThat(retry.onFailure(1, failure, 0, Outcome.SUCCESS)).isEqualTo(RetryPolicy.NO_RETRY);
        assertThat(retry.onFailure(1, failure, 0, Outcome.IGNORE)).isEqualTo(RetryPolicy.NO_RETRY);
    }

    @Test
    void theVerdictReplacesRetryOnButAbortOnStillWins() {
        RetryPolicy retry = retry(RetryConfig.builder().maxAttempts(5)
                .retryOn(List.of(IOException.class)).abortOn(List.of(IllegalArgumentException.class)));

        // Outside retryOn, but the classifier says FAILURE: retried.
        assertThat(retry.onFailure(1, new IllegalStateException(), 0, Outcome.FAILURE)).isNotNegative();
        // Inside retryOn, but the classifier says SUCCESS: not retried.
        assertThat(retry.onFailure(1, new IOException(), 0, Outcome.SUCCESS)).isEqualTo(RetryPolicy.NO_RETRY);
        // abortOn wins over the classifier.
        assertThat(retry.onFailure(1, new IllegalArgumentException(), 0, Outcome.FAILURE)).isEqualTo(RetryPolicy.NO_RETRY);
        // And the list-based method is unchanged.
        assertThat(retry.onFailure(1, new IllegalStateException(), 0)).isEqualTo(RetryPolicy.NO_RETRY);
    }

    @Test
    void anOpenBreakerStillStopsClassifiedRetriesByDefault() {
        RetryPolicy retry = retry(RetryConfig.builder());

        assertThat(retry.onFailure(1, new CallNotPermittedException("cb"), 0, Outcome.FAILURE))
                .isEqualTo(RetryPolicy.NO_RETRY);
        assertThat(retry.onFailure(1, new InterruptedException(), 0, Outcome.FAILURE)).isEqualTo(RetryPolicy.NO_RETRY);
    }

    // --- Retry, async

    private static final Classifier<Integer> STATUS = (result, failure) -> {
        if (failure != null) {
            return failure instanceof IOException ? Outcome.FAILURE : Outcome.IGNORE;
        }
        return result >= 500 ? Outcome.FAILURE : Outcome.SUCCESS;
    };

    @Test
    void asyncRetriesRejectedResultsAndReturnsTheLastOne() throws Exception {
        RetryPolicy retry = new RetryPolicy(RetryConfig.builder().maxAttempts(3).delay(Duration.ZERO).build());
        AtomicInteger calls = new AtomicInteger();

        CompletableFuture<Integer> exhausted = retry.executeAsync(
                () -> CompletableFuture.completedFuture(500 + calls.incrementAndGet()), STATUS, null, null);
        assertThat(exhausted.get(5, TimeUnit.SECONDS)).isEqualTo(503);

        calls.set(0);
        CompletableFuture<Integer> recovered = retry.executeAsync(
                () -> CompletableFuture.completedFuture(calls.incrementAndGet() < 2 ? 503 : 200), STATUS, null, null);
        assertThat(recovered.get(5, TimeUnit.SECONDS)).isEqualTo(200);
        assertThat(calls).hasValue(2);
    }

    @Test
    void asyncRetriesFailuresTheClassifierRejectsOnly() throws Exception {
        RetryPolicy retry = new RetryPolicy(RetryConfig.builder().maxAttempts(3).delay(Duration.ZERO).build());
        AtomicInteger calls = new AtomicInteger();

        CompletableFuture<Integer> ignored = retry.executeAsync(() -> {
            calls.incrementAndGet();
            throw new IllegalStateException("not retried");
        }, STATUS, null, null);
        assertThatThrownBy(() -> ignored.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(IllegalStateException.class);
        assertThat(calls).hasValue(1);

        calls.set(0);
        CompletableFuture<Integer> retried = retry.executeAsync(() -> {
            calls.incrementAndGet();
            return CompletableFuture.failedFuture(new IOException("retried"));
        }, STATUS, null, null);
        assertThatThrownBy(() -> retried.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(IOException.class);
        assertThat(calls).hasValue(3);
    }

    @Test
    void anAsyncClassifierThatThrowsEndsTheRetriesThroughTheFallback() throws Exception {
        RetryPolicy retry = new RetryPolicy(RetryConfig.builder().maxAttempts(3).delay(Duration.ZERO).build());
        FallbackPolicy fallback = new FallbackPolicy("test", List.of(Throwable.class), List.of());
        IllegalStateException broken = new IllegalStateException("classifier bug");
        Classifier<Integer> throwing = (result, failure) -> {
            throw broken;
        };
        List<Object> seen = new ArrayList<>();

        CompletableFuture<Integer> result = retry.executeAsync(() -> CompletableFuture.completedFuture(1), throwing,
                fallback, (failure, attempts) -> {
                    seen.add(failure);
                    seen.add(attempts);
                    return CompletableFuture.completedFuture(-1);
                });

        assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo(-1);
        assertThat(seen).containsExactly(broken, 1);

        CompletableFuture<Integer> unhandled = retry.executeAsync(() -> CompletableFuture.completedFuture(1), throwing,
                null, null);
        assertThatThrownBy(() -> unhandled.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                .hasCause(broken);
    }

    // --- Circuit breaker

    @Test
    void failureResultsOpenTheBreaker() {
        CircuitBreakerPolicy cb = breaker(window(2));
        for (int i = 0; i < 2; i++) {
            long token = cb.acquire();
            cb.onResult(token, cb.startTimer(), Outcome.FAILURE);
        }
        assertThat(cb.state()).isEqualTo(CircuitBreakerState.OPEN);
    }

    @Test
    void successResultsKeepTheBreakerClosedWithoutReadingTheClock() {
        List<String> reads = new ArrayList<>();
        CircuitBreakerPolicy cb = new CircuitBreakerPolicy("test", window(10).build(), () -> {
            reads.add("read");
            return 0;
        });
        for (int i = 0; i < 1000; i++) {
            long token = cb.acquire();
            cb.onResult(token, cb.startTimer(), Outcome.SUCCESS);
        }
        assertThat(cb.state()).isEqualTo(CircuitBreakerState.CLOSED);
        assertThat(reads).isEmpty();
    }

    @Test
    void ignoredResultsAreNotCountedAndGiveBackTheProbe() {
        CircuitBreakerPolicy cb = breaker(window(2));
        for (int i = 0; i < 5; i++) {
            long token = cb.acquire();
            cb.onResult(token, cb.startTimer(), Outcome.IGNORE);
        }
        assertThat(cb.closedWindowCounts()[0]).isZero();

        openAndWait(cb);
        long probe = cb.acquire();
        assertThat(cb.state()).isEqualTo(CircuitBreakerState.HALF_OPEN);
        assertThat(cb.acquire()).isEqualTo(CircuitBreakerPolicy.REJECTED);
        cb.onResult(probe, 0, Outcome.IGNORE);
        assertThat(cb.state()).isEqualTo(CircuitBreakerState.HALF_OPEN);
        long next = cb.acquire();
        assertThat(next).isNotNegative(); // the permit came back
        cb.onResult(next, 0, Outcome.SUCCESS);
        assertThat(cb.state()).isEqualTo(CircuitBreakerState.CLOSED);
    }

    @Test
    void aFailureResultProbeReopensTheBreaker() {
        CircuitBreakerPolicy cb = breaker(window(2));
        openAndWait(cb);
        long probe = cb.acquire();
        cb.onResult(probe, 0, Outcome.FAILURE);
        assertThat(cb.state()).isEqualTo(CircuitBreakerState.OPEN);
    }

    @Test
    void slowFailureResultsCountAsSlowCalls() {
        CircuitBreakerPolicy cb = breaker(window(2).failureRateThreshold(100).slowCallDuration(Duration.ofMillis(100))
                .slowCallRateThreshold(50));
        long token = cb.acquire();
        long start = cb.startTimer();
        now.addAndGet(Duration.ofMillis(150).toNanos());
        cb.onResult(token, start, Outcome.FAILURE);
        token = cb.acquire();
        cb.onResult(token, cb.startTimer(), Outcome.SUCCESS);

        assertThat(cb.state()).isEqualTo(CircuitBreakerState.OPEN); // 1 of 2 slow: 50 %, while failures are 50 % < 100 %
    }

    @Test
    void theVerdictReplacesRecordOnButIgnoreOnStillWins() {
        CircuitBreakerPolicy cb = breaker(window(2).halfOpenCalls(1)
                .recordOn(List.of(IOException.class)).ignoreOn(List.of(IllegalArgumentException.class)));

        long token = cb.acquire();
        cb.onFailure(token, 0, new IllegalArgumentException(), Outcome.FAILURE); // ignoreOn wins
        assertThat(cb.closedWindowCounts()[0]).isZero();

        token = cb.acquire();
        cb.onFailure(token, 0, new IOException(), Outcome.SUCCESS); // in recordOn, but the classifier says success
        assertThat(cb.closedWindowCounts()[1]).isZero();

        token = cb.acquire();
        cb.onFailure(token, 0, new IllegalStateException(), Outcome.FAILURE); // outside recordOn, recorded anyway
        assertThat(cb.state()).isEqualTo(CircuitBreakerState.OPEN);

        now.addAndGet(Duration.ofSeconds(10).toNanos());
        long probe = cb.acquire();
        cb.onFailure(probe, 0, new IOException(), Outcome.IGNORE);
        assertThat(cb.acquire()).isNotNegative(); // an ignored exception gives the probe back
    }

    @Test
    void asyncBreakerRecordsAsTheClassifierSays() throws Exception {
        CircuitBreakerPolicy cb = breaker(window(2));

        assertThat(cb.executeAsync(() -> CompletableFuture.completedFuture(503), STATUS).get()).isEqualTo(503);
        assertThat(cb.executeAsync(() -> CompletableFuture.completedFuture(200), STATUS).get()).isEqualTo(200);
        assertThat(cb.state()).isEqualTo(CircuitBreakerState.OPEN); // one failure in two: 50 %

        cb.reset();
        CompletableFuture<Integer> ignored = cb.executeAsync(
                () -> CompletableFuture.failedFuture(new IllegalStateException()), STATUS);
        assertThatThrownBy(ignored::get).hasCauseInstanceOf(IllegalStateException.class);
        assertThat(cb.closedWindowCounts()[0]).isZero();
    }

    @Test
    void anAsyncClassifierThatThrowsIsRecordedAsAFailure() {
        CircuitBreakerPolicy cb = breaker(window(2));
        openAndWait(cb);
        IllegalStateException broken = new IllegalStateException("classifier bug");
        Classifier<Integer> throwing = (result, failure) -> {
            throw broken;
        };

        CompletableFuture<Integer> result = cb.executeAsync(() -> CompletableFuture.completedFuture(200), throwing);

        assertThatThrownBy(result::get).hasCause(broken);
        assertThat(cb.state()).isEqualTo(CircuitBreakerState.OPEN); // the probe was recorded, as a failure
    }

    private void openAndWait(CircuitBreakerPolicy cb) {
        for (int i = 0; i < 2; i++) {
            long token = cb.acquire();
            cb.onResult(token, 0, Outcome.FAILURE);
        }
        assertThat(cb.state()).isEqualTo(CircuitBreakerState.OPEN);
        now.addAndGet(Duration.ofSeconds(10).toNanos());
    }
}
