package dev.resilience4cata.generated;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.resilience4cata.BulkheadConfig;
import dev.resilience4cata.BulkheadFullException;
import dev.resilience4cata.CallNotPermittedException;
import dev.resilience4cata.CallTimeoutException;
import dev.resilience4cata.CircuitBreakerConfig;
import dev.resilience4cata.CircuitBreakerState;
import dev.resilience4cata.RateLimitExceededException;
import dev.resilience4cata.RateLimiterConfig;
import dev.resilience4cata.RetryConfig;
import dev.resilience4cata.TimeoutConfig;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AsyncPoliciesTest {

    private static Throwable failureOf(CompletableFuture<?> future) {
        assertThat(future).isCompletedExceptionally();
        return future.handle((value, failure) -> failure).join();
    }

    @Test
    void theBreakerRecordsTheOutcomeWhenTheStageCompletes() {
        CircuitBreakerPolicy breaker = new CircuitBreakerPolicy("test", CircuitBreakerConfig.builder()
                .windowSize(2).minimumCalls(2).failureRateThreshold(50).build(), () -> 0L);
        CompletableFuture<String> pending = new CompletableFuture<>();
        CompletableFuture<String> first = breaker.executeAsync(() -> pending);
        CompletableFuture<String> second = breaker.executeAsync(() -> {
            throw new IOException("thrown");
        });
        assertThat(failureOf(second)).isInstanceOf(IOException.class);
        assertThat(breaker.state()).as("one outcome so far").isEqualTo(CircuitBreakerState.CLOSED);

        pending.completeExceptionally(new CompletionException(new IllegalStateException("late")));
        assertThat(failureOf(first)).as("unwrapped").isInstanceOf(IllegalStateException.class);
        assertThat(breaker.state()).isEqualTo(CircuitBreakerState.OPEN);

        AtomicInteger calls = new AtomicInteger();
        CompletableFuture<String> rejected = breaker.executeAsync(() -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture("x");
        });
        assertThat(failureOf(rejected)).isInstanceOf(CallNotPermittedException.class);
        assertThat(calls).hasValue(0);
    }

    @Test
    void theBulkheadFreesTheSlotWhetherTheCallThrowsFailsOrSucceeds() {
        BulkheadPolicy bulkhead = new BulkheadPolicy("test", BulkheadConfig.builder().maxConcurrent(1).build());
        assertThat(failureOf(bulkhead.executeAsync(() -> {
            throw new IOException();
        }))).isInstanceOf(IOException.class);
        assertThat(bulkhead.concurrentCalls()).isZero();

        CompletableFuture<String> pending = new CompletableFuture<>();
        CompletableFuture<String> held = bulkhead.executeAsync(() -> pending);
        assertThat(failureOf(bulkhead.executeAsync(() -> CompletableFuture.completedFuture("x"))))
                .isInstanceOf(BulkheadFullException.class);
        pending.completeExceptionally(new IllegalStateException());
        assertThat(failureOf(held)).isInstanceOf(IllegalStateException.class);
        assertThat(bulkhead.concurrentCalls()).isZero();
        assertThat(bulkhead.executeAsync(() -> CompletableFuture.completedFuture("x")).join()).isEqualTo("x");
    }

    @Test
    void theRateLimiterReservesWithinMaxWaitAndRejectsWithoutWriting() {
        long[] now = {0};
        RateLimiterPolicy limiter = new RateLimiterPolicy("test", RateLimiterConfig.builder().permits(1)
                .period(Duration.ofSeconds(1)).maxWait(Duration.ofMillis(1500)).build(), () -> now[0], nanos -> { });
        assertThat(limiter.reserve()).isZero();
        assertThat(limiter.reserve()).isEqualTo(1_000_000_000L);
        assertThat(limiter.reserve()).as("2 s away").isEqualTo(-1);
        assertThat(limiter.reserve()).as("a rejection reserved nothing").isEqualTo(-1);

        now[0] = 600_000_000L;
        assertThat(limiter.reserve()).isEqualTo(1_400_000_000L);
        assertThat(failureOf(limiter.executeAsync(() -> CompletableFuture.completedFuture("x"))))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void aStageThatCompletesInTimeCancelsItsTimer() throws Exception {
        TimeoutPolicy timeout = new TimeoutPolicy("test", TimeoutConfig.builder().timeout(Duration.ofHours(1)).build());
        int queued = AsyncSupport.queuedTimers();
        CompletableFuture<String> pending = new CompletableFuture<>();
        CompletableFuture<String> result = timeout.executeAsync(() -> pending);
        assertThat(AsyncSupport.queuedTimers()).isEqualTo(queued + 1);

        pending.complete("done");
        assertThat(result.get(1, TimeUnit.SECONDS)).isEqualTo("done");
        assertThat(AsyncSupport.queuedTimers()).as("removed on cancel").isEqualTo(queued);
    }

    @Test
    void aStageThatCompletesAfterTheTimeoutDoesNotChangeTheResult() throws Exception {
        TimeoutPolicy timeout = new TimeoutPolicy("test", TimeoutConfig.builder().timeout(Duration.ofMillis(20)).build());
        CompletableFuture<String> pending = new CompletableFuture<>();
        CompletableFuture<String> result = timeout.executeAsync(() -> pending);
        assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS)).cause().isInstanceOf(CallTimeoutException.class);
        assertThat(pending).as("cancelled").isCancelled();
        assertThat(pending.complete("late")).isFalse();
        assertThat(failureOf(result)).isInstanceOf(CallTimeoutException.class);
    }

    @Test
    void retriesUntilTheLastAttemptAndKeepsItsFailure() throws Exception {
        RetryPolicy retry = new RetryPolicy(RetryConfig.builder().maxAttempts(3).delay(Duration.ZERO).build());
        AtomicInteger calls = new AtomicInteger();
        CompletableFuture<String> result = retry.executeAsync(() ->
                CompletableFuture.failedFuture(new IOException("attempt " + calls.incrementAndGet())));
        assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS)).cause().hasMessage("attempt 3");
        assertThat(calls).hasValue(3);
    }

    @Test
    void anAsyncMethodThatReturnsNullFails() throws Exception {
        RetryPolicy retry = new RetryPolicy(RetryConfig.builder().maxAttempts(1).build());
        CompletableFuture<String> result = retry.executeAsync(() -> null);
        assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS)).cause()
                .isInstanceOf(NullPointerException.class).hasMessageContaining("returned null");
    }

    @Test
    void theRetryHandsTheFinalFailureAndTheAttemptsToTheFallback() throws Exception {
        RetryPolicy retry = new RetryPolicy(RetryConfig.builder().maxAttempts(2).delay(Duration.ZERO).build());
        FallbackPolicy fallback = new FallbackPolicy("test", List.of(IOException.class), List.of());
        CompletableFuture<String> recovered = retry.executeAsync(
                () -> CompletableFuture.failedFuture(new IOException("down")), fallback,
                (failure, attempts) -> CompletableFuture.completedFuture(failure.getMessage() + " after " + attempts));
        assertThat(recovered.get(5, TimeUnit.SECONDS)).isEqualTo("down after 2");

        CompletableFuture<String> skipped = retry.executeAsync(
                () -> CompletableFuture.failedFuture(new IllegalStateException("not handled")), fallback,
                (failure, attempts) -> CompletableFuture.completedFuture("unexpected"));
        assertThatThrownBy(() -> skipped.get(5, TimeUnit.SECONDS)).cause().isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aFallbackThatThrowsOrReturnsNullFailsTheCall() {
        FallbackPolicy fallback = new FallbackPolicy("test", List.of(Throwable.class), List.of());
        CompletableFuture<String> thrown = fallback.executeAsync(() -> CompletableFuture.failedFuture(new IOException()),
                (failure, attempts) -> {
                    throw new IllegalStateException("fallback failed");
                });
        assertThat(failureOf(thrown)).hasMessage("fallback failed");

        CompletableFuture<String> nothing = fallback.executeAsync(() -> CompletableFuture.failedFuture(new IOException()),
                (failure, attempts) -> null);
        assertThat(failureOf(nothing)).isInstanceOf(NullPointerException.class);
    }
}
