package dev.resilience4cata.generated;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.resilience4cata.BulkheadConfig;
import dev.resilience4cata.CallNotPermittedException;
import dev.resilience4cata.CircuitBreakerConfig;
import dev.resilience4cata.HedgeCancelledException;
import dev.resilience4cata.HedgeConfig;
import dev.resilience4cata.Ticker;
import dev.resilience4cata.TimeoutConfig;
import dev.resilience4cata.CallTimeoutException;
import dev.resilience4cata.event.EventListener;
import dev.resilience4cata.event.HedgeEvent;
import dev.resilience4cata.event.ResilienceEvent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongConsumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** {@link HedgePolicy}: timing, winner selection, losers, failures, the budget, on a manual ticker. */
class HedgePolicyTest {

    private static final Duration DELAY = Duration.ofMillis(50);
    private static final long DELAY_NANOS = DELAY.toNanos();

    private final ManualTicker ticker = new ManualTicker();
    private final List<ResilienceEvent> events = new CopyOnWriteArrayList<>();
    private final PolicyEnvironment environment = PolicyEnvironment.of(ticker, null).withEvents(EventDispatcher.of(
            List.of(new EventDispatcher.Subscription(ResilienceEvent.class, null,
                    (EventListener<ResilienceEvent>) events::add))));
    private final Attempts attempts = new Attempts();

    private HedgePolicy hedge(java.util.function.Consumer<HedgeConfig.Builder> config) {
        HedgeConfig.Builder builder = HedgeConfig.builder().delay(DELAY).budgetPercent(0);
        config.accept(builder);
        return new HedgePolicy("h", builder.build(), environment);
    }

    private HedgePolicy hedge() {
        return hedge(b -> { });
    }

    private List<HedgeEvent> hedgeEvents() {
        return events.stream().filter(HedgeEvent.class::isInstance).map(HedgeEvent.class::cast).toList();
    }

    // ---- timing and winners

    @Test
    void theFirstAttemptWinsBeforeTheDelay() {
        CompletableFuture<String> result = hedge().executeAsync(attempts);
        assertThat(attempts.started()).isOne();
        assertThat(ticker.pendingTasks()).as("the delay is armed").isOne();

        attempts.get(0).complete("first");

        assertThat(result).isCompletedWithValue("first");
        assertThat(ticker.pendingTasks()).as("the delay is taken off the ticker").isZero();
        ticker.advance(DELAY_NANOS * 10);
        assertThat(attempts.started()).as("no hedge after the call ended").isOne();
        assertThat(events).as("the success path publishes nothing").isEmpty();
    }

    @Test
    void aHedgeStartsExactlyAfterTheDelayAndWins() {
        CompletableFuture<String> result = hedge().executeAsync(attempts);
        ticker.advance(DELAY_NANOS - 1);
        assertThat(attempts.started()).as("never early").isOne();
        ticker.advance(1);
        assertThat(attempts.started()).as("exactly at the delay, on a manual ticker").isEqualTo(2);
        assertThat(hedgeEvents()).containsExactly(new HedgeEvent.Hedged("h", DELAY_NANOS, 2, HedgeEvent.Reason.DELAY));

        attempts.get(1).complete("second");

        assertThat(result).isCompletedWithValue("second");
        assertThat(attempts.get(0)).as("the loser is cancelled").isCancelled();
        // The exception the loser was completed with; join() wraps it in a new CancellationException on JDK 23+.
        assertThat(attempts.get(0).handle((value, failure) -> failure).join()).isInstanceOf(HedgeCancelledException.class);
        assertThat(hedgeEvents()).last().isEqualTo(new HedgeEvent.Won("h", DELAY_NANOS, 2));
        attempts.get(0).complete("late");
        assertThat(result).as("a late success changes nothing").isCompletedWithValue("second");
    }

    @Test
    void theFirstAttemptCanStillWinAfterAHedgeStarted() {
        CompletableFuture<String> result = hedge().executeAsync(attempts);
        ticker.advance(DELAY_NANOS);
        attempts.get(0).complete("first");
        assertThat(result).isCompletedWithValue("first");
        assertThat(attempts.get(1)).as("the hedge lost").isCancelled();
        assertThat(hedgeEvents()).as("a first-attempt win publishes no Won")
                .noneMatch(HedgeEvent.Won.class::isInstance);
    }

    @Test
    void delaysAreCountedFromTheLatestAttemptUpToMaxAttempts() {
        CompletableFuture<String> result = hedge(b -> b.maxAttempts(3)).executeAsync(attempts);
        ticker.advance(DELAY_NANOS);
        assertThat(attempts.started()).isEqualTo(2);
        ticker.advance(DELAY_NANOS - 1);
        assertThat(attempts.started()).isEqualTo(2);
        ticker.advance(1);
        assertThat(attempts.started()).isEqualTo(3);
        assertThat(ticker.pendingTasks()).as("no fourth attempt is armed").isZero();
        ticker.advance(DELAY_NANOS * 10);
        assertThat(attempts.started()).isEqualTo(3);

        attempts.get(2).complete("third");
        assertThat(result).isCompletedWithValue("third");
        assertThat(attempts.get(0)).isCancelled();
        assertThat(attempts.get(1)).isCancelled();
    }

    @Test
    void aZeroDelayStartsEveryAttemptAtOnce() {
        CompletableFuture<String> result = hedge(b -> b.maxAttempts(3).delay(Duration.ZERO)).executeAsync(attempts);
        assertThat(attempts.started()).isEqualTo(3);
        assertThat(ticker.pendingTasks()).isZero();
        attempts.get(1).complete("second");
        assertThat(result).isCompletedWithValue("second");
        assertThat(attempts.get(0)).isCancelled();
        assertThat(attempts.get(2)).isCancelled();
    }

    @Test
    void anAlreadyCompletedFirstAttemptNeedsNoTimer() {
        CompletableFuture<String> result = hedge().executeAsync(() -> CompletableFuture.completedFuture("cached"));
        assertThat(result).isCompletedWithValue("cached");
        assertThat(ticker.pendingTasks()).isZero();
        assertThat(result).as("a plain copy, not a LinkedFuture").isExactlyInstanceOf(CompletableFuture.class);
    }

    // ---- failures

    @Test
    void aHedgeableFailureStartsTheNextAttemptAtOnce() {
        CompletableFuture<String> result = hedge().executeAsync(attempts);
        attempts.get(0).completeExceptionally(new IOException("down"));
        assertThat(attempts.started()).as("not on the thread that completed the failed attempt").isOne();
        ticker.advance(0);
        assertThat(attempts.started()).as("no wait for the delay").isEqualTo(2);
        assertThat(hedgeEvents()).containsExactly(new HedgeEvent.Hedged("h", 0, 2, HedgeEvent.Reason.FAILURE));
        attempts.get(1).complete("second");
        assertThat(result).isCompletedWithValue("second");
        assertThat(ticker.pendingTasks()).as("the stale delay is cancelled").isZero();
    }

    @Test
    void aDelegateThatThrowsOrReturnsNullIsAFailedAttempt() {
        AtomicInteger calls = new AtomicInteger();
        CompletableFuture<String> result = hedge().executeAsync(() -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("thrown, not returned");
            }
            return null;
        });
        ticker.advance(0);
        assertThat(calls).hasValue(2);
        assertThat(result).isCompletedExceptionally();
        assertThatThrownBy(result::join).hasCauseInstanceOf(NullPointerException.class);
    }

    @Test
    void whenEveryAttemptFailsTheLastFailureWins() {
        CompletableFuture<String> result = hedge().executeAsync(attempts);
        ticker.advance(DELAY_NANOS);
        attempts.get(1).completeExceptionally(new IOException("second"));
        assertThat(result).as("attempt 1 is still in flight").isNotDone();
        IOException last = new IOException("first, last to fail");
        attempts.get(0).completeExceptionally(last);
        assertThatThrownBy(result::join).isInstanceOf(CompletionException.class).hasCause(last);
        assertThat(hedgeEvents()).last().isEqualTo(new HedgeEvent.Failed("h", DELAY_NANOS, 2, last));
    }

    @Test
    void abortOnFailsFastAndCancelsTheOthers() {
        CompletableFuture<String> result = hedge(b -> b.maxAttempts(3).abortOn(List.of(IllegalArgumentException.class)))
                .executeAsync(attempts);
        ticker.advance(DELAY_NANOS);
        IllegalArgumentException abort = new IllegalArgumentException("definitive");
        attempts.get(1).completeExceptionally(abort);
        assertThatThrownBy(result::join).hasCause(abort);
        assertThat(attempts.get(0)).isCancelled();
        assertThat(ticker.pendingTasks()).isZero();
        assertThat(hedgeEvents()).contains(new HedgeEvent.Skipped("h", DELAY_NANOS, 3, HedgeEvent.SkipReason.ABORTED),
                new HedgeEvent.Failed("h", DELAY_NANOS, 2, abort));
    }

    @Test
    void aFailureNotInHedgeOnEndsTheCallToo() {
        CompletableFuture<String> result = hedge(b -> b.hedgeOn(List.of(IOException.class))).executeAsync(attempts);
        IllegalStateException bug = new IllegalStateException("bug");
        attempts.get(0).completeExceptionally(bug);
        assertThat(attempts.started()).isOne();
        assertThatThrownBy(result::join).hasCause(bug);
    }

    @Test
    void aRejectionStopsNewAttemptsButKeepsThoseInFlight() {
        CompletableFuture<String> result = hedge(b -> b.maxAttempts(3)).executeAsync(attempts);
        ticker.advance(DELAY_NANOS);
        attempts.get(1).completeExceptionally(new CallNotPermittedException("cb"));
        assertThat(attempts.started()).as("no attempt replaces a rejected one").isEqualTo(2);
        ticker.advance(DELAY_NANOS * 10);
        assertThat(attempts.started()).as("nor does the delay start one").isEqualTo(2);
        assertThat(hedgeEvents()).contains(new HedgeEvent.Skipped("h", DELAY_NANOS, 3, HedgeEvent.SkipReason.REJECTED));
        attempts.get(0).complete("first");
        assertThat(result).isCompletedWithValue("first");
    }

    @Test
    void aRejectionWithNothingElseInFlightFailsTheCall() {
        CallNotPermittedException rejected = new CallNotPermittedException("cb");
        CompletableFuture<String> result = hedge().executeAsync(() -> CompletableFuture.failedFuture(rejected));
        assertThatThrownBy(result::join).hasCause(rejected);
        assertThat(ticker.pendingTasks()).isZero();
    }

    @Test
    void theFallbackRunsOnceWithTheAttemptsStarted() {
        HedgePolicy hedge = hedge();
        FallbackPolicy fallback = new FallbackPolicy("h", List.of(Throwable.class), List.of(), environment);
        List<Integer> seen = new ArrayList<>();
        CompletableFuture<String> result = hedge.executeAsync(attempts, fallback, (failure, attempted) -> {
            seen.add(attempted);
            return CompletableFuture.completedFuture("fallback after " + failure.getMessage());
        });
        attempts.get(0).completeExceptionally(new IOException("one"));
        ticker.advance(0);
        attempts.get(1).completeExceptionally(new IOException("two"));
        assertThat(result).isCompletedWithValue("fallback after two");
        assertThat(seen).containsExactly(2);
    }

    // ---- losers and the policies inside

    @Test
    void aLosingAttemptIsNotRecordedByTheBreakerInside() {
        CircuitBreakerPolicy breaker = new CircuitBreakerPolicy("cb", CircuitBreakerConfig.builder().windowSize(10)
                .minimumCalls(1).build(), environment);
        HedgePolicy hedge = hedge();
        CompletableFuture<String> result = hedge.executeAsync(() -> breaker.executeAsync(attempts));
        ticker.advance(DELAY_NANOS);
        attempts.get(1).complete("second");
        assertThat(result).isCompletedWithValue("second");
        assertThat(attempts.get(0)).as("cancelled through the breaker's future").isCancelled();
        assertThat(breaker.snapshot().bufferedCalls()).as("only the winner is recorded").isOne();
        assertThat(breaker.snapshot().failedCalls()).as("the loser is not a failure").isZero();
    }

    @Test
    void aLosingAttemptFreesItsBulkheadSlotAtOnce() {
        BulkheadPolicy bulkhead = new BulkheadPolicy("b", BulkheadConfig.builder().maxConcurrent(2).build(), environment);
        CompletableFuture<String> result = hedge().executeAsync(() -> bulkhead.executeAsync(attempts));
        ticker.advance(DELAY_NANOS);
        assertThat(bulkhead.concurrentCalls()).isEqualTo(2);
        attempts.get(1).complete("second");
        assertThat(result).isCompletedWithValue("second");
        assertThat(bulkhead.concurrentCalls()).as("both slots are free").isZero();
    }

    @Test
    void aTimedOutAttemptIsAHedgeableFailure() {
        TimeoutPolicy timeout = new TimeoutPolicy("t", TimeoutConfig.builder().timeout(Duration.ofMillis(30)).build(),
                environment);
        CompletableFuture<String> result = hedge().executeAsync(() -> timeout.executeAsync(attempts));
        ticker.advance(Duration.ofMillis(30).toNanos());
        assertThat(attempts.started()).as("the timeout starts the hedge before the delay").isEqualTo(2);
        assertThat(attempts.get(0)).as("the timeout cancelled it").isCancelled();
        assertThat(hedgeEvents()).first().isEqualTo(new HedgeEvent.Hedged("h", Duration.ofMillis(30).toNanos(), 2,
                HedgeEvent.Reason.FAILURE));
        ticker.advance(Duration.ofMillis(30).toNanos());
        assertThatThrownBy(result::join).hasCauseInstanceOf(CallTimeoutException.class);
    }

    @Test
    void cancellingTheCallCancelsEveryAttemptAndTheDelay() {
        HedgePolicy hedge = hedge(b -> b.maxAttempts(3));
        FallbackPolicy fallback = new FallbackPolicy("h", List.of(Throwable.class), List.of(), environment);
        AtomicInteger recoveries = new AtomicInteger();
        CompletableFuture<String> result = hedge.executeAsync(attempts, fallback, (failure, attempted) -> {
            recoveries.incrementAndGet();
            return CompletableFuture.completedFuture("fallback");
        });
        ticker.advance(DELAY_NANOS);
        assertThat(ticker.pendingTasks()).isOne();

        assertThat(result.cancel(true)).isTrue();

        assertThat(attempts.get(0)).isCancelled();
        assertThat(attempts.get(1)).isCancelled();
        assertThat(ticker.pendingTasks()).isZero();
        ticker.advance(DELAY_NANOS * 10);
        assertThat(attempts.started()).isEqualTo(2);
        assertThat(recoveries).as("no fallback for a cancel").hasValue(0);
        assertThat(hedgeEvents()).hasSize(1); // only the Hedged event
    }

    /**
     * The launch/settle race, replayed: the hedge's own call settles the call (attempt 1 succeeds) after the
     * launcher counted the attempt but before it published the attempt's stage, so the settler did not see it.
     */
    @Test
    void anAttemptThatStartsWhileTheCallSettlesIsCancelledByItsLauncher() {
        CompletableFuture<String> first = new CompletableFuture<>();
        CompletableFuture<String> second = new CompletableFuture<>();
        AtomicInteger calls = new AtomicInteger();
        CompletableFuture<String> result = hedge().executeAsync(() -> {
            if (calls.incrementAndGet() == 1) {
                return first;
            }
            first.complete("first"); // settles before this attempt's stage is published
            return second;
        });
        ticker.advance(DELAY_NANOS);
        assertThat(result).isCompletedWithValue("first");
        assertThat(second).as("its launcher cancelled it").isCancelled();
    }

    @Test
    void aStaleDelayStartsNothing() {
        CompletableFuture<String> result = hedge(b -> b.maxAttempts(3)).executeAsync(attempts);
        attempts.get(0).completeExceptionally(new IOException("down"));
        ticker.advance(0);
        assertThat(attempts.started()).isEqualTo(2);
        ticker.advance(DELAY_NANOS - 1);
        assertThat(attempts.started()).as("the first delay was replaced, not left to fire early").isEqualTo(2);
        ticker.advance(1);
        assertThat(attempts.started()).as("counted from attempt 2's start").isEqualTo(3);
        attempts.get(2).complete("third");
        assertThat(result).isCompletedWithValue("third");
    }

    @Test
    void launchesAfterTheCallSettledStartNothing() {
        HedgePolicy hedge = hedge(b -> b.maxAttempts(3));
        CompletableFuture<String> first = new CompletableFuture<>();
        HedgePolicy.HedgeCall<String> call = hedge.startForTest(attempts, first);
        int started = attempts.started();
        first.complete("first"); // settles; then a delay or failure that read the progress before tries to launch
        assertThat(call.launch(HedgeEvent.Reason.DELAY, -1)).as("settled: no launch").isFalse();
        assertThat(attempts.started()).isEqualTo(started);
        assertThat(call.result).isCompletedWithValue("first");
    }

    // ---- budget

    @Test
    void anExhaustedBudgetSkipsTheHedgeAndTheCallWaitsForAttemptOne() {
        HedgePolicy hedge = hedge(b -> b.budgetPercent(10).budgetMinHedgesPerSecond(0));
        CompletableFuture<String> result = hedge.executeAsync(attempts);
        ticker.advance(DELAY_NANOS);
        assertThat(attempts.started()).as("10 % of one call is not a hedge").isOne();
        assertThat(hedgeEvents()).containsExactly(new HedgeEvent.Skipped("h", DELAY_NANOS, 2,
                HedgeEvent.SkipReason.BUDGET_EXHAUSTED));
        attempts.get(0).complete("first");
        assertThat(result).isCompletedWithValue("first");
        assertThat(hedge.budgetSnapshot()).isNotNull();
        assertThat(hedge.budgetSnapshot().calls()).isOne();
        assertThat(hedge.budgetSnapshot().hedges()).isZero();
    }

    @Test
    void theBudgetAllowsAPercentageOfTheCalls() {
        HedgePolicy hedge = hedge(b -> b.budgetPercent(10).budgetMinHedgesPerSecond(0));
        List<CompletableFuture<String>> results = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            results.add(hedge.executeAsync(attempts));
        }
        ticker.advance(DELAY_NANOS);
        assertThat(attempts.started()).as("20 calls, two hedges").isEqualTo(22);
        assertThat(hedge.budgetSnapshot().hedges()).isEqualTo(2);
        assertThat(hedgeEvents()).filteredOn(HedgeEvent.Skipped.class::isInstance).hasSize(18);
    }

    @Test
    void theReserveAllowsHedgesForARarelyCalledMethod() {
        HedgePolicy hedge = new HedgePolicy("h", HedgeConfig.builder().delay(DELAY).build(), environment); // defaults
        hedge.executeAsync(attempts);
        ticker.advance(DELAY_NANOS);
        assertThat(attempts.started()).as("1 hedge per second of a 10 s window").isEqualTo(2);
    }

    // ---- disabled, config

    @Test
    void aDisabledHedgeCallsOnceAndStillFallsBack() {
        HedgePolicy hedge = hedge(b -> b.enabled(false));
        FallbackPolicy fallback = new FallbackPolicy("h", List.of(Throwable.class), List.of(), environment);
        CompletableFuture<String> result = hedge.executeAsync(attempts, fallback,
                (failure, attempted) -> CompletableFuture.completedFuture("fallback " + attempted));
        ticker.advance(DELAY_NANOS * 10);
        assertThat(attempts.started()).isOne();
        attempts.get(0).completeExceptionally(new IOException("down"));
        assertThat(result).isCompletedWithValue("fallback 1");
        assertThat(hedgeEvents()).isEmpty();
    }

    @Test
    void invalidValuesAreRejectedWithTheirCode() {
        assertThatThrownBy(() -> HedgeConfig.builder().maxAttempts(1).build())
                .hasMessageStartingWith("[R4C-REG-001] Invalid hedge config: maxAttempts must be between 2 and 10");
        assertThatThrownBy(() -> HedgeConfig.builder().maxAttempts(11).build()).hasMessageContaining("R4C-REG-001");
        assertThatThrownBy(() -> HedgeConfig.builder().delay(Duration.ofMillis(-1)).build())
                .hasMessageContaining("delay must not be negative");
        assertThatThrownBy(() -> HedgeConfig.builder().budgetPercent(Double.NaN).build()).hasMessageContaining("budgetPercent");
        assertThatThrownBy(() -> HedgeConfig.builder().budgetMinHedgesPerSecond(-1).build())
                .hasMessageContaining("budgetMinHedgesPerSecond");
        assertThatThrownBy(() -> HedgeConfig.builder().budgetWindow(Duration.ofMillis(10)).build())
                .hasMessageContaining("budgetWindow must be between 1s and 1h");
        assertThat(HedgeConfig.DEFAULTS.hasBudget()).isTrue();
        assertThat(HedgeConfig.builder().budgetPercent(0).build().hasBudget()).isFalse();
    }

    @Test
    void verdictsFollowTheListsAndTreatRejectionsApart() {
        HedgePolicy hedge = hedge(b -> b.hedgeOn(List.of(IOException.class, CallNotPermittedException.class))
                .abortOn(List.of(java.io.FileNotFoundException.class)));
        assertThat(hedge.verdictForTest(new IOException())).isEqualTo(HedgePolicy.Verdict.HEDGE);
        assertThat(hedge.verdictForTest(new java.io.FileNotFoundException())).isEqualTo(HedgePolicy.Verdict.ABORT);
        assertThat(hedge.verdictForTest(new UncheckedIOException(new IOException()))).isEqualTo(HedgePolicy.Verdict.ABORT);
        assertThat(hedge.verdictForTest(new CallNotPermittedException("cb"))).isEqualTo(HedgePolicy.Verdict.REJECTED);
        assertThat(hedge.verdictForTest(new dev.resilience4cata.BulkheadFullException("b")))
                .isEqualTo(HedgePolicy.Verdict.REJECTED);
    }

    // ---- the production timer

    @Test
    void onTheSharedTimerTheFirstDelayIsTheCallItself() {
        List<Runnable> launched = new ArrayList<>();
        TimeoutTimer timer = new TimeoutTimer(launched::add);
        PolicyEnvironment real = PolicyEnvironment.forTest(() -> 0L, RetryPolicy.Sleeper.NONE);
        HedgePolicy hedge = new HedgePolicy("h", HedgeConfig.builder().delay(DELAY).budgetPercent(0).build(), real, timer);

        CompletableFuture<String> won = hedge.executeAsync(attempts);
        timer.tick(0);
        assertThat(timer.pending()).isOne();
        attempts.get(0).complete("first");
        assertThat(won).isCompletedWithValue("first");
        timer.tick(1);
        assertThat(timer.pending()).as("cancelled and unlinked").isZero();

        CompletableFuture<String> hedged = hedge.executeAsync(attempts);
        timer.tick(10);
        timer.tick(10 + DELAY_NANOS);
        assertThat(launched).hasSize(1);
        launched.get(0).run(); // what the timer's virtual thread runs
        assertThat(attempts.started()).isEqualTo(3);
        attempts.get(2).complete("hedge");
        assertThat(hedged).isCompletedWithValue("hedge");
        assertThat(attempts.get(1)).isCancelled();
    }

    @Test
    void aFailureStartsTheNextAttemptOnAVirtualThreadNotOnTheCompletingThread() throws Exception {
        PolicyEnvironment real = PolicyEnvironment.forTest(() -> 0L, RetryPolicy.Sleeper.NONE);
        HedgePolicy hedge = new HedgePolicy("h", HedgeConfig.builder().delay(Duration.ofHours(1)).budgetPercent(0).build(),
                real, new TimeoutTimer(task -> { }));
        List<Thread> callers = new CopyOnWriteArrayList<>();
        java.util.concurrent.CountDownLatch second = new java.util.concurrent.CountDownLatch(1);
        CompletableFuture<String> first = new CompletableFuture<>();
        CompletableFuture<String> result = hedge.executeAsync(() -> {
            callers.add(Thread.currentThread());
            if (callers.size() == 1) {
                return first;
            }
            second.countDown();
            return CompletableFuture.completedFuture("second");
        });
        Thread ioThread = Thread.ofPlatform().name("client-io").start(
                () -> first.completeExceptionally(new IOException("down")));
        ioThread.join();

        assertThat(second.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(result.get(5, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("second");
        assertThat(callers.get(1)).as("not the client's I/O thread").isNotSameAs(ioThread);
        assertThat(callers.get(1).isVirtual()).isTrue();
    }

    @Test
    void eachLoserGetsItsOwnCancellation() {
        CompletableFuture<String> result = hedge(b -> b.maxAttempts(3)).executeAsync(attempts);
        ticker.advance(DELAY_NANOS);
        ticker.advance(DELAY_NANOS);
        attempts.get(2).complete("third");
        assertThat(result).isCompletedWithValue("third");
        Throwable first = attempts.get(0).handle((value, failure) -> failure).join();
        Throwable second = attempts.get(1).handle((value, failure) -> failure).join();
        assertThat(first).isInstanceOf(HedgeCancelledException.class).isNotSameAs(second);
        assertThat(second).isInstanceOf(HedgeCancelledException.class);
        assertThat(first.getStackTrace()).as("stackless").isEmpty();
        assertThat(first.getMessage()).contains("Hedge 'h' cancelled this attempt");
        assertThat(((HedgeCancelledException) first).instanceId()).isEqualTo("h");
    }

    // ---- helpers

    /** The delegate: each call starts an attempt whose stage the test completes. */
    private static final class Attempts implements AsyncCall<String>, Supplier<CompletableFuture<String>> {
        private final List<CompletableFuture<String>> stages = new CopyOnWriteArrayList<>();

        @Override
        public CompletionStage<String> call() {
            return get();
        }

        @Override
        public CompletableFuture<String> get() {
            CompletableFuture<String> stage = new CompletableFuture<>();
            stages.add(stage);
            return stage;
        }

        CompletableFuture<String> get(int index) {
            return stages.get(index);
        }

        int started() {
            return stages.size();
        }
    }

    /** A manual ticker that runs due tasks inline, in deadline order, as testkit's ManualClock does. */
    private static final class ManualTicker implements Ticker {
        private record Task(long due, FutureTask<Void> future) {
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
            tasks.add(new Task(now + delayNanos, future));
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
                    due = tasks.stream().filter(task -> task.due - target <= 0)
                            .min(java.util.Comparator.comparingLong(Task::due)).orElse(null);
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
    }
}
