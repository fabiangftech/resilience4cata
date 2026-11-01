package dev.resilience4cata.generated;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.AdaptiveLimitConfig;
import dev.resilience4cata.CallTimeoutException;
import dev.resilience4cata.CircuitBreakerConfig;
import dev.resilience4cata.Ticker;
import dev.resilience4cata.TimeoutConfig;
import dev.resilience4cata.event.EventListener;
import dev.resilience4cata.event.ResilienceEvent;
import dev.resilience4cata.event.TimeoutEvent;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;
import org.junit.jupiter.api.Test;

/**
 * The streaming forms of the policies ({@code executeStreamAsync(call, elements)}), which the reactor
 * module uses for methods that return a {@code Flux}: a timeout restarted by every element, and a
 * circuit breaker and adaptive limit that time a stream until its first element.
 */
class StreamPoliciesTest {

    private static final long SECOND = TimeUnit.SECONDS.toNanos(1);

    private final ManualTicker ticker = new ManualTicker();
    private final List<ResilienceEvent> events = new CopyOnWriteArrayList<>();
    private final PolicyEnvironment environment = PolicyEnvironment.of(ticker, null)
            .withEvents(EventDispatcher.of(List.of(new EventDispatcher.Subscription(ResilienceEvent.class, null,
                    (EventListener<ResilienceEvent>) events::add))));
    private final FakeStream stream = new FakeStream();

    /** The elements side of one streaming call: the listeners each attempt registered, told of its elements. */
    private static final class FakeStream implements Elements {
        final List<ElementListener> every = new ArrayList<>();
        final List<ElementListener> first = new ArrayList<>();

        @Override
        public void listen(ElementListener listener, boolean everyElement) {
            (everyElement ? every : first).add(listener);
        }

        void element() {
            first.forEach(ElementListener::element);
            first.clear();
            every.forEach(ElementListener::element);
        }
    }

    private TimeoutPolicy timeout(Duration duration) {
        return new TimeoutPolicy("t", TimeoutConfig.builder().timeout(duration).build(), environment);
    }

    // ---- timeout

    @Test
    void aStreamWithoutAFirstElementTimesOut() {
        CompletableFuture<Void> attempt = new CompletableFuture<>();
        CompletableFuture<Void> result = timeout(Duration.ofSeconds(1)).executeStreamAsync(() -> attempt, stream);
        assertThat(stream.every).as("listens to every element").hasSize(1);

        ticker.advance(SECOND - 1);
        assertThat(result).isNotDone();
        ticker.advance(1);
        assertThat(result).isCompletedExceptionally();
        assertThat(result.exceptionNow()).isInstanceOf(CallTimeoutException.class);
        assertThat(attempt).as("the attempt is cancelled").isCancelled();
        assertThat(events).hasExactlyElementsOfTypes(TimeoutEvent.TimedOut.class);
        assertThat(ticker.pendingTasks()).isZero();
    }

    @Test
    void everyElementRestartsTheDeadline() {
        CompletableFuture<Void> attempt = new CompletableFuture<>();
        CompletableFuture<Void> result = timeout(Duration.ofSeconds(1)).executeStreamAsync(() -> attempt, stream);
        for (int i = 0; i < 10; i++) {
            ticker.advance(SECOND * 6 / 10);
            stream.element();
        }
        assertThat(result).as("6 s of elements 0.6 s apart").isNotDone();

        ticker.advance(SECOND - 1);
        assertThat(result).as("just before a second of silence").isNotDone();
        ticker.advance(1);
        assertThat(result.exceptionNow()).isInstanceOf(CallTimeoutException.class);
        assertThat(attempt).isCancelled();
        assertThat(ticker.pendingTasks()).isZero();
    }

    @Test
    void aSilenceLongerThanTheTimeoutBetweenElementsTimesOut() {
        CompletableFuture<Void> attempt = new CompletableFuture<>();
        CompletableFuture<Void> result = timeout(Duration.ofSeconds(1)).executeStreamAsync(() -> attempt, stream);
        ticker.advance(SECOND / 10);
        stream.element();
        ticker.advance(SECOND + SECOND / 10);
        assertThat(result.exceptionNow()).isInstanceOf(CallTimeoutException.class);
    }

    @Test
    void aStreamThatEndsInTimeLeavesNothingScheduled() {
        CompletableFuture<Void> attempt = new CompletableFuture<>();
        CompletableFuture<Void> result = timeout(Duration.ofSeconds(1)).executeStreamAsync(() -> attempt, stream);
        ticker.advance(SECOND / 2);
        stream.element();
        ticker.advance(SECOND * 3 / 4); // past the first deadline: a re-check is scheduled
        assertThat(ticker.pendingTasks()).isOne();
        attempt.complete(null);
        assertThat(result).isCompleted();
        assertThat(ticker.pendingTasks()).as("the re-check is cancelled").isZero();
        assertThat(events).isEmpty();
    }

    @Test
    void cancellingTheResultCancelsTheAttemptAndTheDeadline() {
        CompletableFuture<Void> attempt = new CompletableFuture<>();
        CompletableFuture<Void> result = timeout(Duration.ofSeconds(1)).executeStreamAsync(() -> attempt, stream);
        ticker.advance(SECOND / 2);
        stream.element();
        ticker.advance(SECOND * 3 / 4); // past the first deadline: a re-check is scheduled
        result.cancel(true);
        assertThat(attempt).isCancelled();
        assertThat(ticker.pendingTasks()).isZero();
        ticker.advance(10 * SECOND);
        assertThat(events).isEmpty();
    }

    @Test
    void aFailureIsRelayedAndAnAttemptDoneAtOnceNeedsNoDeadline() {
        CompletableFuture<Void> failed = timeout(Duration.ofSeconds(1))
                .executeStreamAsync(() -> CompletableFuture.failedFuture(new IOException("x")), stream);
        assertThat(failed.exceptionNow()).isInstanceOf(IOException.class);
        CompletableFuture<Void> attempt = new CompletableFuture<>();
        CompletableFuture<Void> later = timeout(Duration.ofSeconds(1)).executeStreamAsync(() -> attempt, stream);
        attempt.completeExceptionally(new IOException("y"));
        assertThat(later.exceptionNow()).isInstanceOf(IOException.class);
        assertThat(ticker.pendingTasks()).isZero();
    }

    @Test
    void aDisabledTimeoutListensToNothing() {
        TimeoutPolicy disabled = new TimeoutPolicy("t", TimeoutConfig.builder().enabled(false).build(), environment);
        CompletableFuture<Void> attempt = new CompletableFuture<>();
        CompletableFuture<Void> result = disabled.executeStreamAsync(() -> attempt, stream);
        assertThat(stream.every).isEmpty();
        ticker.advance(100 * SECOND);
        attempt.complete(null);
        assertThat(result).isCompleted();
    }

    @Test
    void onTheRealTimerAStreamThatKeepsEmittingDoesNotTimeOutAndASilentOneDoes() throws Exception {
        long timeout = TimeUnit.MILLISECONDS.toNanos(200);
        TimeoutPolicy policy = new TimeoutPolicy("t", TimeoutConfig.builder().timeout(Duration.ofNanos(timeout)).build(),
                PolicyEnvironment.DEFAULT);
        CompletableFuture<Void> attempt = new CompletableFuture<>();
        long previous = System.nanoTime();
        CompletableFuture<Void> result = policy.executeStreamAsync(() -> attempt, stream);
        long longestGap = 0;
        for (int i = 0; i < 15; i++) {
            Thread.sleep(40);
            stream.element();
            long now = System.nanoTime();
            longestGap = Math.max(longestGap, now - previous);
            previous = now;
        }
        // On a slow machine a 40 ms sleep can last longer than the timeout: then the stream did go silent for that long.
        if (longestGap < timeout) {
            assertThat(result).as("600 ms of elements about 40 ms apart").isNotDone();
        }
        long silenceStarted = previous;
        assertThat(result).failsWithin(Duration.ofSeconds(5));
        assertThat(System.nanoTime() - silenceStarted).as("never early").isGreaterThanOrEqualTo(timeout);
        // The timeout fails the result, then cancels the attempt, on the timer's thread.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!attempt.isCancelled() && System.nanoTime() - deadline < 0) {
            Thread.onSpinWait();
        }
        assertThat(attempt).isCancelled();
    }

    // ---- circuit breaker

    private CircuitBreakerPolicy breaker() {
        return new CircuitBreakerPolicy("cb", CircuitBreakerConfig.builder().windowSize(4).minimumCalls(4)
                .slowCallDuration(Duration.ofSeconds(1)).slowCallRateThreshold(100).build(), environment);
    }

    @Test
    void theBreakerTimesAStreamUntilItsFirstElement() {
        CircuitBreakerPolicy policy = breaker();
        CompletableFuture<Void> attempt = new CompletableFuture<>();
        CompletableFuture<Void> result = policy.executeStreamAsync(() -> attempt, stream);
        assertThat(stream.first).as("listens to the first element").hasSize(1);
        ticker.advance(SECOND / 2);
        stream.element();
        ticker.advance(10 * SECOND);
        attempt.complete(null);
        assertThat(result).isCompleted();
        assertThat(policy.snapshot().slowCalls()).as("10.5 s long, first element after 0.5 s").isZero();
        assertThat(policy.snapshot().bufferedCalls()).isOne();
    }

    @Test
    void aStreamWithoutElementsIsTimedToItsEnd() {
        CircuitBreakerPolicy policy = breaker();
        CompletableFuture<Void> attempt = new CompletableFuture<>();
        policy.executeStreamAsync(() -> attempt, stream);
        ticker.advance(2 * SECOND);
        attempt.complete(null);
        assertThat(policy.snapshot().slowCalls()).isOne();
    }

    @Test
    void aSlowFirstElementMakesASlowCallAndAFailureAfterElementsIsAFailure() {
        CircuitBreakerPolicy policy = breaker();
        CompletableFuture<Void> attempt = new CompletableFuture<>();
        CompletableFuture<Void> result = policy.executeStreamAsync(() -> attempt, stream);
        ticker.advance(3 * SECOND);
        stream.element();
        attempt.completeExceptionally(new IOException("broken stream"));
        assertThat(result.exceptionNow()).isInstanceOf(IOException.class);
        assertThat(policy.snapshot().slowCalls()).isOne();
        assertThat(policy.snapshot().failedCalls()).isOne();
    }

    @Test
    void aCancelledStreamRecordsNothing() {
        CircuitBreakerPolicy policy = breaker();
        CompletableFuture<Void> attempt = new CompletableFuture<>();
        policy.executeStreamAsync(() -> attempt, stream).cancel(true);
        assertThat(attempt).isCancelled();
        assertThat(policy.snapshot().bufferedCalls()).isZero();
    }

    @Test
    void withoutSlowCallDetectionTheBreakerListensToNothing() {
        CircuitBreakerPolicy policy = new CircuitBreakerPolicy("cb", CircuitBreakerConfig.builder().build(), environment);
        CompletableFuture<Void> attempt = new CompletableFuture<>();
        policy.executeStreamAsync(() -> attempt, stream);
        assertThat(stream.first).isEmpty();
        attempt.complete(null);
        assertThat(policy.snapshot().bufferedCalls()).isOne();
    }

    // ---- adaptive limit

    @Test
    void theAdaptiveLimitHoldsItsSlotForTheStreamAndSamplesTheTimeToTheFirstElement() {
        AdaptiveLimitPolicy policy = new AdaptiveLimitPolicy("limit", AdaptiveLimitConfig.builder().initialLimit(4)
                .minWindowSamples(1).window(Duration.ofSeconds(1)).build(), environment);
        CompletableFuture<Void> attempt = new CompletableFuture<>();
        CompletableFuture<Void> result = policy.executeStreamAsync(() -> attempt, stream);
        assertThat(stream.first).hasSize(1);
        ticker.advance(SECOND * 3 / 10);
        stream.element();
        ticker.advance(5 * SECOND);
        assertThat(policy.concurrentCalls()).as("held while the stream runs").isOne();
        attempt.complete(null);
        assertThat(result).isCompleted();
        assertThat(policy.concurrentCalls()).isZero();
        assertThat(policy.snapshot().windowLatency()).isEqualTo(Duration.ofMillis(300));
    }

    @Test
    void aDroppedStreamWithoutElementsIsSampledToItsEnd() {
        AdaptiveLimitPolicy policy = new AdaptiveLimitPolicy("limit", AdaptiveLimitConfig.builder().initialLimit(4)
                .minWindowSamples(1).window(Duration.ofSeconds(1)).build(), environment);
        CompletableFuture<Void> attempt = new CompletableFuture<>();
        CompletableFuture<Void> result = policy.executeStreamAsync(() -> attempt, stream);
        ticker.advance(2 * SECOND);
        attempt.completeExceptionally(new CallTimeoutException("t", Duration.ofSeconds(2), null));
        assertThat(result).isCompletedExceptionally();
        assertThat(policy.concurrentCalls()).isZero();
        assertThat(policy.snapshot().windowLatency()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void aCancelledStreamFreesItsSlotWithoutASample() {
        AdaptiveLimitPolicy policy = new AdaptiveLimitPolicy("limit", AdaptiveLimitConfig.builder().initialLimit(4)
                .minWindowSamples(1).window(Duration.ofSeconds(1)).build(), environment);
        CompletableFuture<Void> attempt = new CompletableFuture<>();
        policy.executeStreamAsync(() -> attempt, stream).cancel(true);
        assertThat(attempt).isCancelled();
        assertThat(policy.concurrentCalls()).isZero();
        ticker.advance(2 * SECOND);
        assertThat(policy.snapshot().windows()).isZero();
    }

    @Test
    void aCallDoneAtOnceFreesItsSlotAtOnceAndIsSampled() {
        AdaptiveLimitPolicy policy = new AdaptiveLimitPolicy("limit", AdaptiveLimitConfig.builder().initialLimit(1)
                .minLimit(1).maxLimit(1).minWindowSamples(1).window(Duration.ofSeconds(1)).build(), environment);
        for (int i = 0; i < 3; i++) {
            assertThat(policy.executeAsync(() -> CompletableFuture.completedFuture("v"))).as("a limit of one, call %d", i)
                    .isCompletedWithValue("v");
            assertThat(policy.executeStreamAsync(() -> CompletableFuture.completedFuture((Void) null), stream))
                    .isCompleted();
        }
        assertThat(policy.concurrentCalls()).isZero();
        ticker.advance(SECOND);
        policy.executeAsync(() -> CompletableFuture.completedFuture("v"));
        assertThat(policy.snapshot().windows()).as("the samples ended a window").isOne();
    }

    /** A ticker whose tasks run when it is advanced past their time, in order; cancelled ones never run. */
    private static final class ManualTicker implements Ticker {
        private record Task(long due, FutureTask<Void> future) {
        }

        private final List<Task> tasks = new ArrayList<>();
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
            tasks.add(new Task(now + delayNanos, future));
            return future;
        }

        @Override
        public void onAdvance(LongConsumer listener) {
            // not needed: these tests use no synchronous timeout
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
                    due = tasks.stream().filter(task -> task.due - target <= 0)
                            .min((a, b) -> Long.compare(a.due - b.due, 0)).orElse(null);
                    if (due != null) {
                        tasks.remove(due);
                    }
                }
                if (due == null) {
                    break;
                }
                due.future.run();
            }
        }
    }

    // ---- the shared stage of an ended stream

    @Test
    void theEndedStageCannotBeChanged() {
        CompletableFuture<Void> ended = Elements.ended();
        assertThat(ended.complete(null)).isFalse();
        assertThat(ended.completeExceptionally(new IOException())).isFalse();
        assertThat(ended.cancel(true)).isFalse();
        ended.obtrudeException(new IOException());
        ended.orTimeout(1, TimeUnit.NANOSECONDS);
        assertThat(ended.state()).isEqualTo(Future.State.SUCCESS);
        assertThat(ended.resultNow()).isNull();
    }

    @Test
    void everyPolicyPassesTheEndedStageOnAsItIsAndArmsNothing() {
        CompletableFuture<Void> ended = Elements.ended();
        assertThat(timeout(Duration.ofSeconds(1)).executeStreamAsync(() -> ended, stream)).isSameAs(ended);
        assertThat(timeout(Duration.ofSeconds(1)).<Void>executeAsync(() -> ended)).isSameAs(ended);
        CircuitBreakerPolicy breaker = new CircuitBreakerPolicy("cb", CircuitBreakerConfig.builder().build(), environment);
        assertThat(breaker.executeStreamAsync(() -> ended, stream)).isSameAs(ended);
        assertThat(breaker.<Void>executeAsync(() -> ended)).isSameAs(ended);
        BulkheadPolicy bulkhead = new BulkheadPolicy("b", dev.resilience4cata.BulkheadConfig.builder().build(), environment);
        assertThat(bulkhead.<Void>executeAsync(() -> ended)).isSameAs(ended);
        assertThat(bulkhead.concurrentCalls()).as("the slot is freed").isZero();
        RateLimiterPolicy limiter = new RateLimiterPolicy("r", dev.resilience4cata.RateLimiterConfig.builder().build(), environment);
        assertThat(limiter.<Void>executeAsync(() -> ended)).isSameAs(ended);
        AdaptiveLimitPolicy limit = new AdaptiveLimitPolicy("a", AdaptiveLimitConfig.builder().build(), environment);
        assertThat(limit.executeStreamAsync(() -> ended, stream)).isSameAs(ended);
        assertThat(ticker.pendingTasks()).as("no timeout armed").isZero();
    }
}
