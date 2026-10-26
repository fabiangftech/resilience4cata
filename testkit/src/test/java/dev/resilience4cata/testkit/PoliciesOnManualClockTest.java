package dev.resilience4cata.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.resilience4cata.BulkheadFullException;
import dev.resilience4cata.CallTimeoutException;
import dev.resilience4cata.CircuitBreakerState;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.RetryConfig;
import dev.resilience4cata.generated.BulkheadPolicy;
import dev.resilience4cata.generated.CircuitBreakerPolicy;
import dev.resilience4cata.generated.RateLimiterPolicy;
import dev.resilience4cata.generated.RetryPolicy;
import dev.resilience4cata.generated.TimeoutPolicy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Drives the policies the way generated code does, on a registry that follows a {@link ManualClock}. */
class PoliciesOnManualClockTest {

    private final ManualClock clock = new ManualClock();

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @Test
    void retryBackoffAdvancesTheClockInsteadOfSleeping() throws Throwable {
        ResilienceRegistry registry = ResilienceTesting.registry(clock)
                .retryDefaults(r -> r.maxAttempts(4).delay(Duration.ofSeconds(1)).jitter(RetryConfig.Jitter.NONE))
                .build();
        RetryPolicy retry = registry.retryPolicy("retry", "", "", null);
        List<Long> attemptsAt = new ArrayList<>();

        long started = System.nanoTime();
        String result = retryLoop(retry, () -> {
            attemptsAt.add(clock.read());
            if (attemptsAt.size() < 4) {
                throw new IllegalStateException("down");
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(attemptsAt).containsExactly(0L, 1_000_000_000L, 3_000_000_000L, 7_000_000_000L); // 1 s, 2 s, 4 s
        assertThat(clock.elapsed()).isEqualTo(Duration.ofSeconds(7));
        assertThat(System.nanoTime() - started).isLessThan(Duration.ofSeconds(2).toNanos());
    }

    @Test
    void retryMaxDurationIsMeasuredOnTheClock() {
        ResilienceRegistry registry = ResilienceTesting.registry(clock)
                .retryDefaults(r -> r.maxAttempts(10).delay(Duration.ofSeconds(1)).backoff(RetryConfig.Backoff.FIXED)
                        .jitter(RetryConfig.Jitter.NONE).maxDuration(Duration.ofMillis(2_500)))
                .build();
        RetryPolicy retry = registry.retryPolicy("retry", "", "", null);
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> retryLoop(retry, () -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("down");
        })).hasMessage("down");
        assertThat(attempts).hasValue(3); // at 0, 1 s, 2 s; a third delay would end past 2.5 s
    }

    @Test
    void seededJitterGivesTheSameDelaysOnEveryRun() {
        assertThat(jitteredDelays(7)).isEqualTo(jitteredDelays(7)).isNotEqualTo(jitteredDelays(8));
    }

    private List<Long> jitteredDelays(long seed) {
        ManualClock own = new ManualClock();
        ResilienceRegistry registry = ResilienceTesting.registry(own, seed)
                .retryDefaults(r -> r.maxAttempts(6).delay(Duration.ofSeconds(1)).jitter(RetryConfig.Jitter.FULL))
                .build();
        RetryPolicy retry = registry.retryPolicy("retry", "", "", null);
        List<Long> delays = new ArrayList<>();
        for (int attempt = 1; attempt < 6; attempt++) {
            long delay = retry.onFailure(attempt, new IllegalStateException(), retry.start());
            assertThat(delay).isBetween(0L, Duration.ofSeconds(1L << (attempt - 1)).toNanos());
            delays.add(delay);
        }
        return delays;
    }

    @Test
    void breakerWaitsInOpenOnTheClock() {
        ResilienceRegistry registry = ResilienceTesting.registry(clock)
                .circuitBreakerDefaults(cb -> cb.windowSize(2).minimumCalls(2).waitInOpen(Duration.ofSeconds(30)))
                .build();
        CircuitBreakerPolicy breaker = registry.circuitBreakerPolicy("cb", "", "", null);
        for (int i = 0; i < 2; i++) {
            breaker.onFailure(breaker.acquire(), breaker.startTimer(), new IllegalStateException());
        }
        assertThat(breaker.state()).isEqualTo(CircuitBreakerState.OPEN);

        clock.advance(Duration.ofSeconds(29));
        assertThat(breaker.acquire()).isEqualTo(CircuitBreakerPolicy.REJECTED);

        ResilienceTesting.elapseWaitInOpen(breaker, clock);
        long probe = breaker.acquire();
        assertThat(probe).isNotEqualTo(CircuitBreakerPolicy.REJECTED);
        assertThat(breaker.state()).isEqualTo(CircuitBreakerState.HALF_OPEN);
    }

    @Test
    void slowCallsAreMeasuredOnTheClock() {
        ResilienceRegistry registry = ResilienceTesting.registry(clock)
                .circuitBreakerDefaults(cb -> cb.windowSize(1).minimumCalls(1)
                        .slowCallDuration(Duration.ofSeconds(2)).slowCallRateThreshold(100))
                .build();
        CircuitBreakerPolicy breaker = registry.circuitBreakerPolicy("cb", "", "", null);
        long token = breaker.acquire();
        long start = breaker.startTimer();
        clock.advance(Duration.ofSeconds(2));
        breaker.onSuccess(token, start);

        assertThat(breaker.state()).isEqualTo(CircuitBreakerState.OPEN);
    }

    @Test
    void rateLimiterRefillsAndWaitsOnTheClock() {
        ResilienceRegistry registry = ResilienceTesting.registry(clock)
                .rateLimiterDefaults(rl -> rl.permits(2).period(Duration.ofSeconds(1)).maxWait(Duration.ofSeconds(1)))
                .build();
        RateLimiterPolicy limiter = registry.rateLimiterPolicy("rl", "", "", null);
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(clock.read()).isZero();

        assertThat(limiter.tryAcquire()).isTrue(); // waits half a second for the next permit, on the clock
        assertThat(clock.elapsed()).isEqualTo(Duration.ofMillis(500));

        clock.advance(Duration.ofSeconds(1));
        assertThat(limiter.availablePermits()).isEqualTo(2);
    }

    @Test
    void aSyncCallSleepingOnTheClockTimesOut() throws Throwable {
        ResilienceRegistry registry = ResilienceTesting.registry(clock)
                .timeoutDefaults(t -> t.timeout(Duration.ofSeconds(1)))
                .build();
        TimeoutPolicy timeout = registry.timeoutPolicy("t", "", "", null);

        assertThatThrownBy(() -> timed(timeout, () -> {
            clock.sleep(Duration.ofSeconds(5));
            return "late";
        })).isInstanceOf(CallTimeoutException.class).hasCauseInstanceOf(InterruptedException.class);
        assertThat(Thread.currentThread().isInterrupted()).isFalse();

        // Exactly at the limit, not before.
        assertThat(timed(timeout, () -> {
            clock.sleep(Duration.ofMillis(999));
            return "in time";
        })).isEqualTo("in time");
        assertThatThrownBy(() -> timed(timeout, () -> {
            clock.sleep(Duration.ofSeconds(1));
            return "late";
        })).isInstanceOf(CallTimeoutException.class);
    }

    // java:S2925: the call must really block, so that only the timeout's interrupt ends it
    @SuppressWarnings("java:S2925")
    @Test
    void aSyncCallBlockedElsewhereTimesOutWhenAnotherThreadAdvancesTheClock() throws Exception {
        ResilienceRegistry registry = ResilienceTesting.registry(clock)
                .timeoutDefaults(t -> t.timeout(Duration.ofSeconds(1)))
                .build();
        TimeoutPolicy timeout = registry.timeoutPolicy("t", "", "", null);
        CompletableFuture<Throwable> outcome = new CompletableFuture<>();
        CompletableFuture<Void> started = new CompletableFuture<>();
        Thread caller = Thread.ofPlatform().start(() -> {
            try {
                timed(timeout, () -> {
                    started.complete(null);
                    Thread.sleep(Duration.ofMinutes(1)); // a real block, only an interrupt ends it
                    return "late";
                });
                outcome.complete(null);
            } catch (Throwable e) {
                outcome.complete(e);
            }
        });
        started.join();

        clock.advance(Duration.ofMillis(999));
        assertThat(outcome).isNotDone();
        clock.advance(Duration.ofMillis(1));

        assertThat(outcome.get(10, TimeUnit.SECONDS)).isInstanceOf(CallTimeoutException.class);
        caller.join();
    }

    @Test
    void asyncRetriesRunWhenTheClockPassesTheirBackoff() {
        ResilienceRegistry registry = ResilienceTesting.registry(clock)
                .retryDefaults(r -> r.maxAttempts(3).delay(Duration.ofSeconds(1)).jitter(RetryConfig.Jitter.NONE))
                .build();
        RetryPolicy retry = registry.retryPolicy("retry", "", "", null);
        List<Long> attemptsAt = new ArrayList<>();

        CompletableFuture<String> result = retry.executeAsync(() -> {
            attemptsAt.add(clock.read());
            return attemptsAt.size() < 3
                    ? CompletableFuture.failedFuture(new IllegalStateException("down"))
                    : CompletableFuture.completedFuture("ok");
        });

        assertThat(attemptsAt).containsExactly(0L);
        assertThat(result).isNotDone();
        clock.advance(Duration.ofSeconds(1));
        assertThat(attemptsAt).containsExactly(0L, 1_000_000_000L);
        assertThat(clock.advanceUntilIdle()).isEqualTo(Duration.ofSeconds(2));
        assertThat(result).isCompletedWithValue("ok");
        assertThat(attemptsAt).containsExactly(0L, 1_000_000_000L, 3_000_000_000L);
    }

    @Test
    void anAsyncRetryWithNoDelayWaitsForTheNextAdvance() {
        ResilienceRegistry registry = ResilienceTesting.registry(clock)
                .retryDefaults(r -> r.maxAttempts(3).delay(Duration.ZERO).jitter(RetryConfig.Jitter.NONE))
                .build();
        RetryPolicy retry = registry.retryPolicy("retry", "", "", null);
        List<Thread> attemptThreads = new ArrayList<>();

        CompletableFuture<String> result = retry.executeAsync(() -> {
            attemptThreads.add(Thread.currentThread());
            return attemptThreads.size() < 3
                    ? CompletableFuture.failedFuture(new IllegalStateException("down"))
                    : CompletableFuture.completedFuture("ok");
        });

        // the next attempt is a task on the clock, not a virtual thread racing the test
        assertThat(attemptThreads).hasSize(1);
        assertThat(clock.pendingTasks()).isOne();
        assertThat(clock.nextTaskIn()).isEqualTo(Duration.ZERO);
        clock.advance(Duration.ZERO);
        assertThat(result).isCompletedWithValue("ok");
        assertThat(attemptThreads).containsOnly(Thread.currentThread()).hasSize(3);
        assertThat(clock.elapsed()).isZero();
    }

    @Test
    void asyncTimeoutsFireWhenTheClockPassesThemAndAreCancelledOtherwise() {
        ResilienceRegistry registry = ResilienceTesting.registry(clock)
                .timeoutDefaults(t -> t.timeout(Duration.ofSeconds(2)))
                .build();
        TimeoutPolicy timeout = registry.timeoutPolicy("t", "", "", null);
        CompletableFuture<String> never = new CompletableFuture<>();
        CompletableFuture<String> later = new CompletableFuture<>();

        CompletableFuture<String> timedOut = timeout.executeAsync(() -> never);
        CompletableFuture<String> completed = timeout.executeAsync(() -> later);
        clock.advance(Duration.ofMillis(1_999));
        assertThat(timedOut).isNotDone();
        later.complete("ok");
        clock.advance(Duration.ofMillis(1));

        assertThat(completed).isCompletedWithValue("ok");
        assertThat(timedOut).isCompletedExceptionally(); // at once, on this thread
        assertThatThrownBy(timedOut::join).isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(CallTimeoutException.class);
        assertThat(never).isCancelled();
        assertThat(clock.pendingTasks()).isZero();
    }

    @Test
    void anAsyncCallThatFinishesInTimeLeavesNoTaskOnTheClock() {
        ResilienceRegistry registry = ResilienceTesting.registry(clock)
                .timeoutDefaults(t -> t.timeout(Duration.ofSeconds(2)))
                .build();
        TimeoutPolicy timeout = registry.timeoutPolicy("t", "", "", null);
        CompletableFuture<String> later = new CompletableFuture<>();

        CompletableFuture<String> completed = timeout.executeAsync(() -> later);
        assertThat(clock.pendingTasks()).isEqualTo(1);
        later.complete("ok");

        assertThat(completed).isCompletedWithValue("ok");
        assertThat(clock.pendingTasks()).isZero(); // before the deadline, not only after it
        assertThat(clock.nextTaskIn()).isNull();
    }

    @Test
    void asyncRateLimiterWaitsForTheClock() {
        ResilienceRegistry registry = ResilienceTesting.registry(clock)
                .rateLimiterDefaults(rl -> rl.permits(1).period(Duration.ofSeconds(1)).maxWait(Duration.ofSeconds(5)))
                .build();
        RateLimiterPolicy limiter = registry.rateLimiterPolicy("rl", "", "", null);

        CompletableFuture<String> first = limiter.executeAsync(() -> CompletableFuture.completedFuture("first"));
        CompletableFuture<String> second = limiter.executeAsync(() -> CompletableFuture.completedFuture("second"));

        assertThat(first).isCompletedWithValue("first");
        assertThat(second).isNotDone();
        clock.advance(Duration.ofSeconds(1));
        assertThat(second).isCompletedWithValue("second");
    }

    @Test
    void asyncBulkheadWaitEndsOnTheClock() {
        ResilienceRegistry registry = ResilienceTesting.registry(clock)
                .bulkheadDefaults(b -> b.maxConcurrent(1).maxWait(Duration.ofSeconds(3)))
                .build();
        BulkheadPolicy bulkhead = registry.bulkheadPolicy("b", "", "", null);
        CompletableFuture<String> busy = new CompletableFuture<>();
        bulkhead.executeAsync(() -> busy);

        CompletableFuture<String> queued = bulkhead.executeAsync(() -> CompletableFuture.completedFuture("ran"));
        clock.advance(Duration.ofMillis(2_999));
        assertThat(queued).isNotDone();
        clock.advance(Duration.ofMillis(1));

        assertThat(queued).isCompletedExceptionally(); // at once, on this thread
        assertThatThrownBy(queued::join).hasCauseInstanceOf(BulkheadFullException.class);
        busy.complete("done");
    }

    /** What generated code does for {@code @Retry}. */
    private static <T> T retryLoop(RetryPolicy retry, Attempt<T> call) throws Throwable {
        long start = retry.start();
        int attempts = 0;
        while (true) {
            try {
                return call.run();
            } catch (Throwable e) {
                long delay = retry.onFailure(++attempts, e, start);
                if (delay < 0 || !retry.pause(delay)) {
                    throw e;
                }
            }
        }
    }

    /** What generated code does for {@code @Timeout}. */
    private static <T> T timed(TimeoutPolicy timeout, Attempt<T> call) throws Throwable {
        TimeoutPolicy.Scope scope = timeout.enter();
        T result;
        try {
            result = call.run();
        } catch (Throwable e) {
            timeout.failed(scope, e);
            throw e;
        }
        timeout.succeeded(scope);
        return result;
    }

    @FunctionalInterface
    private interface Attempt<T> {
        T run() throws Throwable;
    }
}
