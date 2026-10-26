package dev.resilience4cata.generated;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.BulkheadConfig;
import dev.resilience4cata.CircuitBreakerConfig;
import dev.resilience4cata.CircuitBreakerState;
import dev.resilience4cata.Classifier;
import dev.resilience4cata.RateLimiterConfig;
import dev.resilience4cata.RetryConfig;
import dev.resilience4cata.Ticker;
import dev.resilience4cata.TimeoutConfig;
import dev.resilience4cata.event.EventListener;
import dev.resilience4cata.event.ResilienceEvent;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongConsumer;
import org.junit.jupiter.api.Test;

/**
 * Downward cancellation: cancelling the future an async policy returns reaches the stage inside it, and
 * each policy lets go of what it holds without treating the cancel as a failure of the call.
 */
class AsyncCancellationTest {

    private final ManualTicker ticker = new ManualTicker();
    private final List<ResilienceEvent> events = new CopyOnWriteArrayList<>();
    private final PolicyEnvironment environment = PolicyEnvironment.of(ticker, null).withEvents(EventDispatcher.of(
            List.of(new EventDispatcher.Subscription(ResilienceEvent.class, null,
                    (EventListener<ResilienceEvent>) events::add))));

    // ---- LinkedFuture itself

    @Test
    void cancellingTheFutureCancelsTheLinkedStageOnce() {
        CompletableFuture<String> inner = new CompletableFuture<>();
        AtomicInteger hooks = new AtomicInteger();
        LinkedFuture<String> future = new LinkedFuture<>(inner);

        assertThat(future.cancel(true)).isTrue();
        assertThat(inner).isCancelled();
        assertThat(future).isCancelled();
        assertThat(future.cancelling()).isTrue();

        assertThat(future.link((LinkedFuture.OnCancel) hooks::incrementAndGet)).as("linked after the cancel").isFalse();
        assertThat(hooks).as("cancelled at once instead").hasValue(1);
        assertThat(future.cancel(true)).as("still cancelled").isTrue();
        assertThat(hooks).as("a second cancel reaches nothing").hasValue(1);
    }

    @Test
    void completingExceptionallyWithACancellationAlsoGoesDown() {
        CompletableFuture<String> inner = new CompletableFuture<>();
        LinkedFuture<String> future = new LinkedFuture<>(inner);
        assertThat(future.completeExceptionally(new CancellationException("stop"))).isTrue();
        assertThat(inner).isCancelled();

        CompletableFuture<String> other = new CompletableFuture<>();
        LinkedFuture<String> failed = new LinkedFuture<>(other);
        failed.completeExceptionally(new IOException("not a cancel"));
        assertThat(other).as("only a cancellation goes down").isNotDone();
        assertThat(failed.cancelling()).isFalse();
    }

    @Test
    void aCompletedFutureIgnoresTheCancelAndAStageThatRefusesItIsLeftAlone() {
        CompletableFuture<String> inner = new CompletableFuture<>();
        LinkedFuture<String> future = new LinkedFuture<>(inner);
        future.complete("done");
        assertThat(future.cancel(true)).isFalse();
        assertThat(inner).as("the call ended already").isNotDone();

        CompletableFuture<String> source = new CompletableFuture<>();
        LinkedFuture<String> minimal = new LinkedFuture<>(source.minimalCompletionStage());
        assertThat(minimal.cancel(true)).as("a minimal stage throws on cancel; the future is cancelled anyway").isTrue();
        assertThat(source).isNotDone();
    }

    @Test
    void theInternalFailureRelayDoesNotCancelDownward() {
        CompletableFuture<String> inner = new CompletableFuture<>();
        LinkedFuture<String> future = new LinkedFuture<>(inner);
        future.fail(new CancellationException("relayed from inside"));
        assertThat(future).isCancelled();
        assertThat(future.cancelling()).isFalse();
        assertThat(inner).isNotDone();
    }

    // ---- bulkhead

    @Test
    void cancellingAHeldCallFreesTheBulkheadSlotAtOnceAndOnlyOnce() {
        BulkheadPolicy bulkhead = new BulkheadPolicy("b", BulkheadConfig.builder().maxConcurrent(1).build(), environment);
        CompletableFuture<String> stage = new CompletableFuture<>();
        CompletableFuture<String> held = bulkhead.executeAsync(() -> stage);
        assertThat(bulkhead.concurrentCalls()).isOne();
        AtomicInteger seenByCallback = new AtomicInteger(-1);
        held.whenComplete((value, failure) -> seenByCallback.set(bulkhead.concurrentCalls()));

        assertThat(held.cancel(true)).isTrue();

        assertThat(stage).as("cancelled downward").isCancelled();
        assertThat(bulkhead.concurrentCalls()).as("free at once").isZero();
        assertThat(seenByCallback).as("before the caller's callbacks run").hasValue(0);
        stage.complete("late");
        assertThat(bulkhead.concurrentCalls()).as("never released twice").isZero();
        CompletableFuture<String> next = new CompletableFuture<>();
        bulkhead.executeAsync(() -> next);
        assertThat(bulkhead.concurrentCalls()).isOne();
        assertThat(bulkhead.executeAsync(() -> CompletableFuture.completedFuture("x"))).isCompletedExceptionally();
    }

    @Test
    void aCancelledWaiterLeavesTheQueueAndNeverRuns() {
        BulkheadPolicy bulkhead = new BulkheadPolicy("b", BulkheadConfig.builder().maxConcurrent(1)
                .maxWait(Duration.ofSeconds(10)).build(), environment);
        CompletableFuture<String> stage = new CompletableFuture<>();
        CompletableFuture<String> held = bulkhead.executeAsync(() -> stage);
        AtomicInteger calls = new AtomicInteger();
        CompletableFuture<String> waiting = bulkhead.executeAsync(() -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture("ran");
        });
        assertThat(bulkhead.queuedCalls()).isOne();
        assertThat(ticker.pendingTasks()).as("the waiter's timeout").isOne();

        assertThat(waiting.cancel(true)).isTrue();

        assertThat(bulkhead.queuedCalls()).as("left the queue").isZero();
        assertThat(ticker.pendingTasks()).as("its timeout is cancelled").isZero();
        assertThat(bulkhead.waitingCalls()).isZero();
        stage.complete("ok");
        assertThat(held).isCompletedWithValue("ok");
        assertThat(calls).hasValue(0);
        assertThat(bulkhead.concurrentCalls()).isZero();
        assertThat(events).as("a cancel is not a rejection").isEmpty();
    }

    @Test
    void aWaiterCancelledAfterTheHandOffClaimedItGivesTheSlotBack() {
        BulkheadPolicy bulkhead = new BulkheadPolicy("b", BulkheadConfig.builder().maxConcurrent(1)
                .maxWait(Duration.ofSeconds(10)).build(), environment);
        AtomicInteger calls = new AtomicInteger();
        BulkheadWaiters.Waiter<String> waiter = bulkhead.enqueueForTest(() -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture("ran");
        });
        // The drain: takes the free permit and claims the waiter, then grants it on another thread.
        assertThat(bulkhead.tryAcquireForTest()).isTrue();
        assertThat(waiter.claim()).isTrue();
        assertThat(bulkhead.concurrentCalls()).isOne();

        assertThat(waiter.result.cancel(true)).isTrue();
        bulkhead.runGrantedForTest(waiter);

        assertThat(calls).as("the call never starts").hasValue(0);
        assertThat(bulkhead.concurrentCalls()).as("the granted slot is released").isZero();
    }

    @Test
    void aWaiterCancelledWhileItsGrantedCallRunsCancelsThatCall() {
        BulkheadPolicy bulkhead = new BulkheadPolicy("b", BulkheadConfig.builder().maxConcurrent(1)
                .maxWait(Duration.ofSeconds(10)).build(), environment);
        CompletableFuture<String> stage = new CompletableFuture<>();
        BulkheadWaiters.Waiter<String> waiter = bulkhead.enqueueForTest(() -> stage);
        assertThat(bulkhead.tryAcquireForTest()).isTrue();
        assertThat(waiter.claim()).isTrue();
        bulkhead.runGrantedForTest(waiter);

        assertThat(waiter.result.cancel(true)).isTrue();

        assertThat(stage).isCancelled();
        assertThat(bulkhead.concurrentCalls()).isZero();
    }

    // ---- circuit breaker

    @Test
    void aCancelledCallIsNotRecordedAsAFailure() {
        CircuitBreakerPolicy breaker = breaker(1);
        CompletableFuture<String> stage = new CompletableFuture<>();
        CompletableFuture<String> call = breaker.executeAsync(() -> stage);

        call.cancel(true);

        assertThat(stage).isCancelled();
        assertThat(breaker.state()).as("one failure would open it").isEqualTo(CircuitBreakerState.CLOSED);
        assertThat(breaker.snapshot().failedCalls()).isZero();
        assertThat(events).isEmpty();
    }

    @Test
    void aCancelledCallWithAClassifierIsNotClassifiedNorRecorded() {
        CircuitBreakerPolicy breaker = breaker(1);
        AtomicInteger classified = new AtomicInteger();
        Classifier<Object> classifier = (value, failure) -> {
            classified.incrementAndGet();
            return Classifier.Outcome.FAILURE;
        };
        CompletableFuture<String> stage = new CompletableFuture<>();
        CompletableFuture<String> call = breaker.executeAsync(() -> stage, classifier, false);

        call.cancel(true);

        assertThat(classified).hasValue(0);
        assertThat(breaker.state()).isEqualTo(CircuitBreakerState.CLOSED);
    }

    @Test
    void aCancelledHalfOpenProbeGivesItsPermitBack() {
        CircuitBreakerPolicy breaker = breaker(1);
        breaker.executeAsync(() -> CompletableFuture.failedFuture(new IOException("down")));
        assertThat(breaker.state()).isEqualTo(CircuitBreakerState.OPEN);
        ticker.advance(Duration.ofSeconds(2).toNanos());

        CompletableFuture<String> probe = breaker.executeAsync(CompletableFuture::new);
        assertThat(breaker.state()).isEqualTo(CircuitBreakerState.HALF_OPEN);
        probe.cancel(true);

        CompletableFuture<String> next = breaker.executeAsync(() -> CompletableFuture.completedFuture("ok"));
        assertThat(next).as("the permit came back").isCompletedWithValue("ok");
        assertThat(breaker.state()).isEqualTo(CircuitBreakerState.CLOSED);
    }

    private CircuitBreakerPolicy breaker(int window) {
        return new CircuitBreakerPolicy("cb", CircuitBreakerConfig.builder().windowSize(window).minimumCalls(window)
                .failureRateThreshold(50).waitInOpen(Duration.ofSeconds(1)).halfOpenCalls(1).build(), environment);
    }

    // ---- retry

    @Test
    void cancellingDuringTheBackoffStopsTheRetries() {
        RetryPolicy retry = retry();
        AtomicInteger attempts = new AtomicInteger();
        CompletableFuture<String> call = retry.executeAsync(() -> {
            attempts.incrementAndGet();
            return CompletableFuture.failedFuture(new IOException("down"));
        });
        assertThat(ticker.pendingTasks()).as("the backoff").isOne();
        events.clear();

        assertThat(call.cancel(true)).isTrue();

        assertThat(ticker.pendingTasks()).as("the backoff is cancelled").isZero();
        ticker.advance(Duration.ofMinutes(1).toNanos());
        assertThat(attempts).hasValue(1);
        assertThat(events).as("no GaveUp, no retry").isEmpty();
    }

    @Test
    void cancellingDuringAnAttemptCancelsItAndRetriesNothing() {
        RetryPolicy retry = retry();
        List<CompletableFuture<String>> stages = new ArrayList<>();
        AtomicInteger recoveries = new AtomicInteger();
        FallbackPolicy fallback = new FallbackPolicy("f", List.of(Throwable.class), List.of(), environment);
        CompletableFuture<String> call = retry.executeAsync(() -> {
            CompletableFuture<String> stage = new CompletableFuture<>();
            stages.add(stage);
            return stage;
        }, fallback, (failure, attempts) -> {
            recoveries.incrementAndGet();
            return CompletableFuture.completedFuture("fallback");
        });
        stages.get(0).completeExceptionally(new IOException("first"));
        ticker.advance(Duration.ofSeconds(1).toNanos());
        assertThat(stages).hasSize(2);
        events.clear();

        call.cancel(true);

        assertThat(stages.get(1)).isCancelled();
        assertThat(call).isCancelled();
        ticker.advance(Duration.ofMinutes(1).toNanos());
        assertThat(stages).hasSize(2);
        assertThat(recoveries).as("no fallback for a cancelled call").hasValue(0);
        assertThat(ticker.pendingTasks()).isZero();
        assertThat(events).isEmpty();
    }

    @Test
    void aBackoffThatFiresAfterTheCancelStartsNoAttempt() {
        RetryPolicy retry = retry();
        AtomicInteger attempts = new AtomicInteger();
        CompletableFuture<String> call = retry.executeAsync(() -> {
            attempts.incrementAndGet();
            return CompletableFuture.failedFuture(new IOException("down"));
        });

        call.cancel(true);
        ticker.runAllIgnoringCancel(); // as if the timer had fired just before the cancel reached it

        assertThat(attempts).hasValue(1);
    }

    private RetryPolicy retry() {
        return new RetryPolicy("r", RetryConfig.builder().maxAttempts(5).delay(Duration.ofSeconds(1))
                .jitter(RetryConfig.Jitter.NONE).build(), environment);
    }

    // ---- fallback

    @Test
    void theFallbackDoesNotRunForACancelledCallAndItsOwnStageIsCancelled() {
        FallbackPolicy fallback = new FallbackPolicy("f", List.of(Throwable.class), List.of(), environment);
        AtomicInteger recoveries = new AtomicInteger();
        CompletableFuture<String> stage = new CompletableFuture<>();
        CompletableFuture<String> call = fallback.executeAsync(() -> stage, (failure, attempts) -> {
            recoveries.incrementAndGet();
            return CompletableFuture.completedFuture("fallback");
        });
        call.cancel(true);
        assertThat(stage).isCancelled();
        assertThat(recoveries).hasValue(0);
        assertThat(events).isEmpty();

        CompletableFuture<String> recovering = new CompletableFuture<>();
        CompletableFuture<String> second = fallback.executeAsync(() -> CompletableFuture.failedFuture(new IOException()),
                (failure, attempts) -> recovering);
        second.cancel(true);
        assertThat(recovering).as("the fallback's stage is cancelled too").isCancelled();
    }

    // ---- timeout

    @Test
    void cancellingCancelsTheTimeoutAndTheStage() {
        List<Runnable> launched = new ArrayList<>();
        TimeoutTimer timer = new TimeoutTimer(launched::add);
        TimeoutPolicy timeout = new TimeoutPolicy("t", TimeoutConfig.builder().timeout(Duration.ofSeconds(1)).build(),
                null, timer);
        CompletionStageThatIgnoresCancel<String> stubborn = new CompletionStageThatIgnoresCancel<>();
        CompletableFuture<String> call = timeout.executeAsync(() -> stubborn);
        assertThat(timer.pending()).isOne();

        call.cancel(true);

        assertThat(stubborn.cancels).as("asked to stop").hasValue(1);
        timer.tick(1); // the next tick drops cancelled entries
        assertThat(timer.pending()).as("deadline dropped although the stage runs on").isZero();
        timer.tick(Duration.ofMinutes(1).toNanos());
        assertThat(launched).isEmpty();
        assertThat(events).as("no TimedOut").isEmpty();
    }

    @Test
    void aTimeoutAroundAnotherPolicyStillSkipsTheTimerForACompletedStage() {
        TimeoutTimer timer = new TimeoutTimer(task -> { });
        TimeoutPolicy timeout = new TimeoutPolicy("t", TimeoutConfig.builder().timeout(Duration.ofSeconds(1)).build(),
                null, timer);
        BulkheadPolicy bulkhead = new BulkheadPolicy("b", BulkheadConfig.builder().maxConcurrent(1).build());
        CompletableFuture<String> call = timeout.executeAsync(
                () -> bulkhead.executeAsync(() -> CompletableFuture.completedFuture("ok")));
        assertThat(call).isCompletedWithValue("ok");
        assertThat(timer.pending()).as("the bulkhead's future is complete: no deadline needed").isZero();
    }

    @Test
    void cancellingOnAManualTickerCancelsTheDeadlineToo() {
        TimeoutPolicy timeout = new TimeoutPolicy("t", TimeoutConfig.builder().timeout(Duration.ofSeconds(1)).build(),
                environment);
        CompletableFuture<String> stage = new CompletableFuture<>();
        CompletableFuture<String> call = timeout.executeAsync(() -> stage);
        call.cancel(true);
        assertThat(stage).isCancelled();
        assertThat(ticker.pendingTasks()).isZero();
        ticker.advance(Duration.ofMinutes(1).toNanos());
        assertThat(events).isEmpty();
    }

    // ---- rate limiter

    @Test
    void aCallCancelledWhileWaitingForItsPermitGivesThePermitBack() {
        RateLimiterPolicy limiter = new RateLimiterPolicy("l", RateLimiterConfig.builder().permits(1)
                .period(Duration.ofSeconds(1)).maxWait(Duration.ofSeconds(5)).build(), environment);
        assertThat(limiter.executeAsync(() -> CompletableFuture.completedFuture("now"))).isCompletedWithValue("now");
        AtomicInteger calls = new AtomicInteger();
        CompletableFuture<String> delayed = limiter.executeAsync(() -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture("later");
        });
        assertThat(delayed).isNotDone();

        delayed.cancel(true);
        ticker.advance(Duration.ofSeconds(1).toNanos());

        assertThat(calls).hasValue(0);
        assertThat(limiter.reserve()).as("the permit it reserved is free again").isZero();
    }

    @Test
    void aCallCancelledAfterItStartedKeepsItsPermit() {
        RateLimiterPolicy limiter = new RateLimiterPolicy("l", RateLimiterConfig.builder().permits(1)
                .period(Duration.ofSeconds(1)).maxWait(Duration.ofSeconds(5)).build(), environment);
        limiter.executeAsync(() -> CompletableFuture.completedFuture("now"));
        CompletableFuture<String> stage = new CompletableFuture<>();
        CompletableFuture<String> delayed = limiter.executeAsync(() -> stage);
        ticker.advance(Duration.ofSeconds(1).toNanos());

        delayed.cancel(true);

        assertThat(stage).isCancelled();
        assertThat(limiter.reserve()).as("used: the next permit is a period away").isEqualTo(Duration.ofSeconds(1).toNanos());
    }

    // ---- nesting

    @Test
    void aCancelGoesThroughEveryLayer() {
        RetryPolicy retry = retry();
        CircuitBreakerPolicy breaker = breaker(1);
        RateLimiterPolicy limiter = new RateLimiterPolicy("l", RateLimiterConfig.builder().permits(100)
                .period(Duration.ofSeconds(1)).build(), environment);
        TimeoutPolicy timeout = new TimeoutPolicy("t", TimeoutConfig.builder().timeout(Duration.ofSeconds(5)).build(),
                environment);
        BulkheadPolicy bulkhead = new BulkheadPolicy("b", BulkheadConfig.builder().maxConcurrent(1).build(), environment);
        CompletableFuture<String> stage = new CompletableFuture<>();
        AtomicInteger attempts = new AtomicInteger();
        CompletableFuture<String> call = retry.executeAsync(() -> {
            attempts.incrementAndGet();
            return breaker.executeAsync(() -> limiter.executeAsync(() -> timeout.executeAsync(
                    () -> bulkhead.executeAsync(() -> stage))));
        });
        assertThat(bulkhead.concurrentCalls()).isOne();

        call.cancel(true);

        assertThat(stage).isCancelled();
        assertThat(bulkhead.concurrentCalls()).isZero();
        assertThat(breaker.state()).isEqualTo(CircuitBreakerState.CLOSED);
        assertThat(ticker.pendingTasks()).isZero();
        ticker.advance(Duration.ofMinutes(1).toNanos());
        assertThat(attempts).hasValue(1);
        assertThat(events).isEmpty();
    }

    /** A stage that counts cancels but does not complete. */
    private static final class CompletionStageThatIgnoresCancel<T> extends CompletableFuture<T> {
        final AtomicInteger cancels = new AtomicInteger();

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancels.incrementAndGet();
            return false;
        }
    }

    /** Runs scheduled tasks when advanced, on the advancing thread. */
    private static final class ManualTicker implements Ticker {
        private record Task(long due, FutureTask<Void> future, Runnable task) {
        }

        private final List<Task> tasks = new ArrayList<>();
        private final List<LongConsumer> listeners = new ArrayList<>();
        private long now;

        @Override
        public synchronized long read() {
            return now;
        }

        @Override
        public void sleep(long nanos) {
            advance(nanos);
        }

        @Override
        public synchronized Future<?> schedule(long delayNanos, Runnable task) {
            FutureTask<Void> future = new FutureTask<>(task, null);
            tasks.add(new Task(now + delayNanos, future, task));
            return future;
        }

        @Override
        public synchronized void onAdvance(LongConsumer listener) {
            listeners.add(listener);
        }

        synchronized int pendingTasks() {
            return (int) tasks.stream().filter(task -> !task.future.isDone()).count();
        }

        void advance(long nanos) {
            long target;
            synchronized (this) {
                now += nanos;
                target = now;
            }
            while (true) {
                Task due;
                synchronized (this) {
                    due = tasks.stream().filter(task -> task.due - target <= 0).findFirst().orElse(null);
                    if (due != null) {
                        tasks.remove(due);
                    }
                }
                if (due == null) {
                    break;
                }
                due.future.run();
            }
            listeners.forEach(listener -> listener.accept(target));
        }

        /** Runs every task, even cancelled ones: as if each had fired just before its cancel. */
        void runAllIgnoringCancel() {
            List<Task> all;
            synchronized (this) {
                all = new ArrayList<>(tasks);
                tasks.clear();
            }
            all.forEach(task -> task.task.run());
        }
    }
}
