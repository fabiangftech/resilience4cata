package dev.resilience4cata.generated;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.resilience4cata.AdaptiveLimitConfig;
import dev.resilience4cata.BulkheadConfig;
import dev.resilience4cata.CallTimeoutException;
import dev.resilience4cata.CircuitBreakerConfig;
import dev.resilience4cata.CircuitBreakerState;
import dev.resilience4cata.AdaptiveLimitFullException;
import dev.resilience4cata.RateLimiterConfig;
import dev.resilience4cata.RetryConfig;
import dev.resilience4cata.TimeoutConfig;
import dev.resilience4cata.event.AdaptiveLimitEvent;
import dev.resilience4cata.event.BulkheadEvent;
import dev.resilience4cata.event.CircuitBreakerEvent;
import dev.resilience4cata.event.CircuitBreakerEvent.CallNotPermitted;
import dev.resilience4cata.event.CircuitBreakerEvent.StateTransition;
import dev.resilience4cata.event.EventListener;
import dev.resilience4cata.event.FallbackEvent;
import dev.resilience4cata.event.RateLimiterEvent;
import dev.resilience4cata.event.ResilienceEvent;
import dev.resilience4cata.event.RetryEvent;
import dev.resilience4cata.event.TimeoutEvent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class EventPublishingTest {

    private static final long MS = 1_000_000L;

    private final AtomicLong now = new AtomicLong(1_000 * MS);
    private final List<ResilienceEvent> events = new CopyOnWriteArrayList<>();

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    private PolicyEnvironment listening(Class<? extends ResilienceEvent> type) {
        return environment(new EventDispatcher.Subscription(type, null, (EventListener<ResilienceEvent>) events::add));
    }

    private PolicyEnvironment environment(EventDispatcher.Subscription... subscriptions) {
        return PolicyEnvironment.forTest(now::get, now::addAndGet, EventDispatcher.of(List.of(subscriptions)));
    }

    // Circuit breaker

    private CircuitBreakerPolicy breaker(PolicyEnvironment environment) {
        CircuitBreakerConfig config = CircuitBreakerConfig.builder().windowSize(2).minimumCalls(2)
                .failureRateThreshold(50).waitInOpen(Duration.ofSeconds(10)).halfOpenCalls(1).build();
        return new CircuitBreakerPolicy("cb", config, environment);
    }

    private static void fail(CircuitBreakerPolicy cb) {
        long token = cb.acquire();
        cb.onFailure(token, cb.startTimer(), new IOException("boom"));
    }

    @Test
    void circuitBreakerTransitionsArePublishedWithTheirStatesAndTime() {
        CircuitBreakerPolicy cb = breaker(listening(CircuitBreakerEvent.class));
        fail(cb);
        fail(cb);
        assertThat(events).containsExactly(
                new StateTransition("cb", now.get(), CircuitBreakerState.CLOSED, CircuitBreakerState.OPEN));

        now.addAndGet(Duration.ofSeconds(10).toNanos());
        long token = cb.acquire(); // open -> half open, and this call is the probe
        cb.onSuccess(token, cb.startTimer()); // half open -> closed
        cb.forceOpen();
        cb.disable();
        cb.reset();
        cb.reset();

        assertThat(events).extracting(e -> ((StateTransition) e).from() + "->" + ((StateTransition) e).to())
                .containsExactly("CLOSED->OPEN", "OPEN->HALF_OPEN", "HALF_OPEN->CLOSED", "CLOSED->FORCED_OPEN",
                        "FORCED_OPEN->DISABLED", "DISABLED->CLOSED", "CLOSED->CLOSED");
        assertThat(events.get(1).nanoTime()).isEqualTo(now.get());
    }

    @Test
    void rejectionsArePublishedWithTheStateThatRejected() {
        CircuitBreakerPolicy cb = breaker(listening(CallNotPermitted.class));
        fail(cb);
        fail(cb);
        assertThat(cb.acquire()).isEqualTo(CircuitBreakerPolicy.REJECTED);

        now.addAndGet(Duration.ofSeconds(10).toNanos());
        long probe = cb.acquire(); // takes the only half-open permit
        assertThat(probe).isNotNegative();
        assertThat(cb.acquire()).isEqualTo(CircuitBreakerPolicy.REJECTED);
        cb.forceOpen();
        assertThat(cb.acquire()).isEqualTo(CircuitBreakerPolicy.REJECTED);

        assertThat(events).as("only the subscribed type").containsExactly(
                new CallNotPermitted("cb", 1_000 * MS, CircuitBreakerState.OPEN),
                new CallNotPermitted("cb", now.get(), CircuitBreakerState.HALF_OPEN),
                new CallNotPermitted("cb", now.get(), CircuitBreakerState.FORCED_OPEN));
    }

    @Test
    void asyncRejectionsArePublishedToo() {
        CircuitBreakerPolicy cb = breaker(listening(CallNotPermitted.class));
        cb.forceOpen();
        assertThat(cb.executeAsync(() -> CompletableFuture.completedFuture("x"))).isCompletedExceptionally();
        assertThat(events).hasSize(1);
    }

    @Test
    void listenersCanBeLimitedToOneInstance() {
        PolicyEnvironment environment = environment(
                new EventDispatcher.Subscription(StateTransition.class, "other", (EventListener<StateTransition>) events::add));
        CircuitBreakerPolicy cb = breaker(environment);
        cb.forceOpen();
        assertThat(events).isEmpty();
        new CircuitBreakerPolicy("other", cb.config(), environment).forceOpen();
        assertThat(events).extracting(ResilienceEvent::id).containsExactly("other");
    }

    @Test
    void aThrowingListenerDoesNotBreakTheCallNorTheOtherListeners() {
        List<Throwable> reported = new ArrayList<>();
        Thread thread = Thread.currentThread();
        Thread.UncaughtExceptionHandler previous = thread.getUncaughtExceptionHandler();
        thread.setUncaughtExceptionHandler((t, e) -> reported.add(e));
        try {
            CircuitBreakerPolicy cb = breaker(environment(
                    new EventDispatcher.Subscription(ResilienceEvent.class, null, e -> {
                        throw new IllegalStateException("listener bug");
                    }),
                    new EventDispatcher.Subscription(ResilienceEvent.class, null, (EventListener<ResilienceEvent>) events::add)));
            cb.forceOpen();
            assertThat(cb.acquire()).isEqualTo(CircuitBreakerPolicy.REJECTED);
            assertThat(cb.state()).isEqualTo(CircuitBreakerState.FORCED_OPEN);
        } finally {
            thread.setUncaughtExceptionHandler(previous);
        }
        assertThat(events).hasSize(2);
        assertThat(reported).hasSize(2).allSatisfy(e -> assertThat(e).hasMessage("listener bug"));
    }

    @Test
    void theDispatcherResolvesNothingWhenNobodyListens() {
        assertThat(EventDispatcher.NONE.sink(StateTransition.class, "cb")).isNull();
        assertThat(EventDispatcher.of(List.of())).isSameAs(EventDispatcher.NONE);
        EventDispatcher dispatcher = EventDispatcher.of(List.of(
                new EventDispatcher.Subscription(RetryEvent.class, null, e -> { }),
                new EventDispatcher.Subscription(CallNotPermitted.class, "a", e -> { })));
        assertThat(dispatcher.sink(StateTransition.class, "a")).as("other type").isNull();
        assertThat(dispatcher.sink(CallNotPermitted.class, "b")).as("other instance").isNull();
        assertThat(dispatcher.sink(CallNotPermitted.class, "a")).isNotNull();
        assertThat(dispatcher.sink(RetryEvent.GaveUp.class, "any")).as("interface matches its records").isNotNull();
    }

    /**
     * Rejections without listeners for them stay allocation-free: an open breaker, a full bulkhead and an
     * exhausted rate limiter, whose only listener is for another event type.
     */
    @Test
    void rejectionsAllocateNothingWithoutListeners() {
        PolicyEnvironment environment = listening(StateTransition.class);
        CircuitBreakerPolicy cb = breaker(environment);
        cb.forceOpen();
        BulkheadPolicy bulkhead = new BulkheadPolicy("b", BulkheadConfig.builder().maxConcurrent(1).build(), environment);
        assertThat(bulkhead.tryEnter()).isTrue();
        RateLimiterPolicy limiter = new RateLimiterPolicy("r",
                RateLimiterConfig.builder().permits(1).period(Duration.ofHours(1)).build(), environment);
        assertThat(limiter.tryAcquire()).isTrue();
        AdaptiveLimitPolicy adaptive = new AdaptiveLimitPolicy("a",
                AdaptiveLimitConfig.builder().initialLimit(1).build(), environment);
        adaptive.acquire();

        java.lang.management.ThreadMXBean mx = java.lang.management.ManagementFactory.getThreadMXBean();
        if (!(mx instanceof com.sun.management.ThreadMXBean threads) || !threads.isThreadAllocatedMemorySupported()) {
            return;
        }
        long rejected = 0;
        for (int round = 0; round < 3; round++) { // warm up, so that the measured round runs compiled code
            long before = threads.getCurrentThreadAllocatedBytes();
            for (int i = 0; i < 100_000; i++) {
                rejected += cb.acquire() < 0 ? 1 : 0;
                rejected += bulkhead.tryEnter() ? 0 : 1;
                rejected += limiter.tryAcquire() ? 0 : 1;
                rejected += rejectedBy(adaptive);
            }
            long allocated = threads.getCurrentThreadAllocatedBytes() - before;
            if (round == 2) {
                assertThat(allocated).as("bytes allocated by 400,000 rejections").isLessThan(16 * 1024);
            }
        }
        assertThat(rejected).isEqualTo(1_200_000);
        assertThat(events).hasSize(1); // the forceOpen transition
    }

    private static int rejectedBy(AdaptiveLimitPolicy adaptive) {
        try {
            adaptive.acquire();
            return 0;
        } catch (AdaptiveLimitFullException e) {
            return 1;
        }
    }

    // Adaptive limit

    @Test
    void adaptiveLimitRejectionsAndLimitChangesArePublished() {
        AdaptiveLimitPolicy adaptive = new AdaptiveLimitPolicy("a", AdaptiveLimitConfig.builder()
                .algorithm(AdaptiveLimitConfig.Algorithm.AIMD).initialLimit(1).minWindowSamples(1)
                .window(Duration.ofMillis(10)).build(), listening(AdaptiveLimitEvent.class));
        long start = adaptive.acquire();
        assertThatThrownBy(adaptive::acquire).isSameAs(adaptive.rejection());
        now.addAndGet(10 * MS);
        adaptive.onSuccess(start); // ends the window: one in flight out of a limit of 1, so AIMD adds one

        assertThat(events).containsExactly(
                new AdaptiveLimitEvent.Rejected("a", 1_000 * MS, 1),
                new AdaptiveLimitEvent.LimitChanged("a", 1_010 * MS, 1, 2, java.time.Duration.ofNanos(10 * MS)));
    }

    // Retry

    private RetryPolicy retry(RetryConfig.Builder config) {
        return new RetryPolicy("retry", config.jitter(RetryConfig.Jitter.NONE).backoff(RetryConfig.Backoff.FIXED).build(), listening(RetryEvent.class));
    }

    @Test
    void retriesAndGivingUpArePublished() {
        RetryPolicy retry = retry(RetryConfig.builder().maxAttempts(3).delay(Duration.ofMillis(100)));
        IOException failure = new IOException("boom");
        long start = retry.start();
        assertThat(retry.onFailure(1, failure, start)).isEqualTo(100 * MS);
        assertThat(retry.onFailure(2, failure, start)).isEqualTo(100 * MS);
        assertThat(retry.onFailure(3, failure, start)).isEqualTo(RetryPolicy.NO_RETRY);

        assertThat(events).containsExactly(
                new RetryEvent.Retrying("retry", now.get(), 1, Duration.ofMillis(100), failure),
                new RetryEvent.Retrying("retry", now.get(), 2, Duration.ofMillis(100), failure),
                new RetryEvent.GaveUp("retry", now.get(), 3, RetryEvent.Reason.MAX_ATTEMPTS, failure));
    }

    @Test
    void theReasonForGivingUpIsReported() {
        RetryPolicy retry = retry(RetryConfig.builder().maxAttempts(5).delay(Duration.ofMillis(100))
                .abortOn(List.of(UncheckedIOException.class)).maxDuration(Duration.ofMillis(150)));
        long start = retry.start();
        retry.onFailure(1, new UncheckedIOException(new IOException()), start);
        Thread.currentThread().interrupt();
        retry.onFailure(1, new IOException(), start);
        Thread.interrupted();
        now.addAndGet(100 * MS);
        retry.onFailure(1, new IOException(), start); // 100 ms elapsed + 100 ms delay > 150 ms
        retry.onFailedResult(1, start - 100 * MS); // a rejected result: no exception

        assertThat(events).extracting(e -> ((RetryEvent.GaveUp) e).reason()).containsExactly(
                RetryEvent.Reason.NOT_RETRYABLE, RetryEvent.Reason.INTERRUPTED, RetryEvent.Reason.MAX_DURATION,
                RetryEvent.Reason.MAX_DURATION);
        assertThat(((RetryEvent.GaveUp) events.get(3)).failure()).isNull();
    }

    @Test
    void aDisabledRetryPublishesNothing() {
        RetryPolicy retry = retry(RetryConfig.builder().enabled(false));
        assertThat(retry.onFailure(1, new IOException(), retry.start())).isEqualTo(RetryPolicy.NO_RETRY);
        assertThat(events).isEmpty();
    }

    // Timeout

    @Test
    void aSynchronousTimeoutIsPublishedBeforeTheExceptionIsThrown() {
        Watchdog watchdog = new Watchdog(0);
        TimeoutPolicy timeout = new TimeoutPolicy("t", TimeoutConfig.builder().timeout(Duration.ofMillis(100)).build(),
                watchdog, null, listening(TimeoutEvent.class));
        TimeoutPolicy.Scope scope = timeout.enter();
        watchdog.tick(100 * MS + watchdog.tick());
        assertThatThrownBy(() -> timeout.succeeded(scope)).isInstanceOf(CallTimeoutException.class);
        assertThat(events).containsExactly(new TimeoutEvent.TimedOut("t", now.get(), Duration.ofMillis(100)));

        TimeoutPolicy.Scope inTime = timeout.enter();
        timeout.succeeded(inTime);
        assertThat(events).hasSize(1);
    }

    @Test
    void anAsynchronousTimeoutIsPublishedWhenItWins() {
        List<Runnable> launched = new ArrayList<>();
        TimeoutTimer timer = new TimeoutTimer(launched::add);
        TimeoutPolicy timeout = new TimeoutPolicy("t", TimeoutConfig.builder().timeout(Duration.ofMillis(100)).build(),
                null, timer, listening(TimeoutEvent.class));
        CompletableFuture<String> late = timeout.executeAsync(CompletableFuture::new);
        CompletableFuture<String> inTime = new CompletableFuture<>();
        CompletableFuture<String> completed = timeout.executeAsync(() -> inTime);
        timer.tick(0);
        inTime.complete("ok");
        timer.tick(Duration.ofSeconds(1).toNanos());
        launched.forEach(Runnable::run);

        assertThat(late).isCompletedExceptionally();
        assertThat(completed).isCompletedWithValue("ok");
        assertThat(events).containsExactly(new TimeoutEvent.TimedOut("t", now.get(), Duration.ofMillis(100)));
    }

    // Bulkhead

    @Test
    void bulkheadRejectionsArePublished() {
        PolicyEnvironment environment = listening(BulkheadEvent.class);
        BulkheadPolicy full = new BulkheadPolicy("b", BulkheadConfig.builder().maxConcurrent(1).build(), environment);
        assertThat(full.tryEnter()).isTrue();
        assertThat(full.tryEnter()).isFalse();
        assertThat(full.executeAsync(() -> CompletableFuture.completedFuture(1))).isCompletedExceptionally();
        assertThat(events).containsExactly(new BulkheadEvent.Rejected("b", now.get(), 1),
                new BulkheadEvent.Rejected("b", now.get(), 1));

        events.clear();
        BulkheadPolicy waiting = new BulkheadPolicy("w",
                BulkheadConfig.builder().maxConcurrent(1).maxWait(Duration.ofMillis(5)).build(), environment);
        assertThat(waiting.tryEnter()).isTrue();
        assertThat(waiting.tryEnter()).as("sync wait times out").isFalse();
        CompletableFuture<Integer> queued = waiting.executeAsync(() -> CompletableFuture.completedFuture(1));
        assertThatThrownBy(() -> queued.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(dev.resilience4cata.BulkheadFullException.class);
        assertThat(events).extracting(ResilienceEvent::id).containsExactly("w", "w");

        events.clear();
        Thread.currentThread().interrupt();
        assertThat(waiting.tryEnter()).as("interrupted wait").isFalse();
        assertThat(events).hasSize(1);
    }

    // Rate limiter

    @Test
    void rateLimiterRejectionsArePublishedWithTheClockReadingOfTheCall() {
        RateLimiterPolicy limiter = new RateLimiterPolicy("r",
                RateLimiterConfig.builder().permits(1).period(Duration.ofSeconds(1)).build(), listening(RateLimiterEvent.class));
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isFalse();
        assertThat(limiter.executeAsync(() -> CompletableFuture.completedFuture(1))).isCompletedExceptionally();
        assertThat(events).containsExactly(new RateLimiterEvent.Rejected("r", now.get()),
                new RateLimiterEvent.Rejected("r", now.get()));
    }

    // Fallback

    @Test
    void aFallbackIsPublishedOnlyForTheFailuresItHandles() {
        FallbackPolicy fallback = new FallbackPolicy("f", List.of(IOException.class), List.of(),
                listening(FallbackEvent.class));
        IOException handled = new IOException();
        assertThat(fallback.handles(handled)).isTrue();
        assertThat(fallback.handles(new IllegalStateException())).isFalse();
        assertThat(events).containsExactly(new FallbackEvent.Applied("f", now.get(), handled));
    }

    @Test
    void aFallbackIsNotPublishedForAFailureNoSiblingTakes() {
        FallbackPolicy fallback = new FallbackPolicy("f", List.of(Throwable.class), List.of(), List.of(IOException.class),
                listening(FallbackEvent.class));
        assertThat(fallback.handles(new AssertionError())).isFalse();
        IOException handled = new IOException();
        assertThat(fallback.handles(handled)).isTrue();
        assertThat(events).containsExactly(new FallbackEvent.Applied("f", now.get(), handled));
    }
}
