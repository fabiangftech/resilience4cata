package dev.resilience4cata.generated;

import dev.resilience4cata.CallRejectedException;
import dev.resilience4cata.HedgeCancelledException;
import dev.resilience4cata.HedgeConfig;
import dev.resilience4cata.HedgeSnapshot;
import dev.resilience4cata.Ticker;
import dev.resilience4cata.event.HedgeEvent;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Future;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;

/**
 * Hedged requests for generated code (asynchronous methods only): the same call is started again when
 * the attempts in flight have produced no result for {@code delay}, or at once when one fails with a
 * failure in {@code hedgeOn}; the first success completes the call and the other attempts are cancelled.
 *
 * <p>One call is one {@code HedgeCall}: the timer entry of its first delay, the completion callback of its
 * first attempt, and the link of the returned future (so a cancel by the caller reaches every attempt).
 * Its progress is one {@code int} changed only by compare-and-set: attempts launched, attempts failed, and
 * two flags, {@code SETTLED} (the call has its outcome) and {@code STOPPED} (no further attempt may start).
 * Exactly one transition sets {@code SETTLED}, so the returned future is completed once; a late success or
 * failure finds it set and is dropped.
 *
 * <p>Losers. The stages of attempts 2 and later are kept in an array created by the first hedge, one slot
 * per attempt. The launcher publishes its attempt's stage with a compare-and-set from {@code null}; the
 * settler swaps a mark into every other slot. Each slot is one location, so exactly one of them sees the
 * other: the settler cancels a stage it found, and a launcher that finds the mark cancels its own stage. A
 * loser's stage is completed with a new, stackless {@link HedgeCancelledException}, which the
 * policies inside it ({@link LinkedFuture}) treat as a cancel: a circuit breaker records nothing, a bulkhead
 * frees its slot.
 *
 * <p>Cost when the first attempt wins before {@code delay}: the returned future, the {@code HedgeCall}
 * and the JDK's callback node; scheduling is one release compare-and-set onto the shared
 * {@link TimeoutTimer}'s per-thread inbox, after one clock read for its start (the delay's lane is precise:
 * a hedge starts at {@code start + delay} plus the OS's wake-up delay, not up to two timer ticks late),
 * cancelling one compare-and-set on the entry; with a hedge budget, one lossy write to this thread's stripe
 * of the budget. A first attempt whose
 * stage is already complete when the delegate returns needs no timer. On a manual {@link Ticker}, delays
 * are scheduled on the ticker (exact) and hedges start on the thread that advances it.
 */
public final class HedgePolicy {

    private static final int LAUNCHED = 0xF;
    private static final int FAILED_ONE = 0x10;
    private static final int FAILED_SHIFT = 4;
    private static final int SETTLED = 0x100;
    private static final int STOPPED = 0x200;

    private static final VarHandle PROGRESS;
    private static final VarHandle STAGES;
    private static final VarHandle TIMER;
    private static final VarHandle SLOT = MethodHandles.arrayElementVarHandle(Object[].class);
    /** In a slot of {@code stages}, or in {@code timer}: the call has settled, cancel what comes. */
    private static final Object DONE = new Object();

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            PROGRESS = lookup.findVarHandle(HedgeCall.class, "progress", int.class);
            STAGES = lookup.findVarHandle(HedgeCall.class, "stages", Object[].class);
            TIMER = lookup.findVarHandle(HedgeCall.class, "timer", Object.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final String id;
    private final HedgeConfig config;
    private final boolean enabled;
    private final int maxAttempts;
    private final long delayNanos;
    private final @Nullable RetryBudget budget;
    // One ClassValue per policy (see RetryPolicy): it returns the whole verdict.
    private final Classification verdicts;
    /**
     * The production timer's lane for {@code delay}; {@code null} when disabled, for {@code delay = 0}, or on a
     * ticker.
     */
    private final TimeoutTimer.@Nullable Lane lane;
    private final PolicyEnvironment environment;
    private final LongSupplier ticker;
    private final @Nullable EventSink<HedgeEvent.Hedged> hedged;
    private final @Nullable EventSink<HedgeEvent.Won> won;
    private final @Nullable EventSink<HedgeEvent.Skipped> skipped;
    private final @Nullable EventSink<HedgeEvent.Failed> failed;

    /**
     * Creates a policy from a resolved configuration.
     *
     * @param id the policy's id
     * @param config the configuration
     * @param environment the clock and timers to use, and the listeners
     */
    @dev.resilience4cata.annotation.Internal
    public HedgePolicy(String id, HedgeConfig config, PolicyEnvironment environment) {
        this(id, config, environment, environment.timeoutTimer());
    }

    /** For tests: a manual {@link TimeoutTimer} instead of the shared one (ignored on a manual ticker). */
    HedgePolicy(String id, HedgeConfig config, PolicyEnvironment environment, @Nullable TimeoutTimer timer) {
        this.id = id;
        this.config = config;
        this.enabled = config.enabled();
        this.maxAttempts = config.maxAttempts();
        this.delayNanos = config.delay().toNanos();
        this.budget = enabled && config.hasBudget() ? new RetryBudget(config, environment.ticker.getAsLong()) : null;
        this.verdicts = new Classification(config.hedgeOn(), config.abortOn());
        this.lane = enabled && delayNanos > 0 && environment.manual == null && timer != null
                ? timer.preciseLane(delayNanos) : null; // no thread starts until the first hedged call
        this.environment = environment;
        this.ticker = environment.ticker;
        this.hedged = environment.events.sink(HedgeEvent.Hedged.class, id);
        this.won = environment.events.sink(HedgeEvent.Won.class, id);
        this.skipped = environment.events.sink(HedgeEvent.Skipped.class, id);
        this.failed = environment.events.sink(HedgeEvent.Failed.class, id);
    }

    /** Returns the configuration this policy was created from. */
    @dev.resilience4cata.annotation.Internal
    public HedgeConfig config() {
        return config;
    }

    /** The budget's current window, for snapshots; {@code null} without a budget. Off the call path. */
    @dev.resilience4cata.annotation.Internal
    public HedgeSnapshot.@Nullable Budget budgetSnapshot() {
        RetryBudget hedgeBudget = this.budget;
        return hedgeBudget != null ? hedgeBudget.hedgeSnapshot(ticker.getAsLong()) : null;
    }

    /**
     * Runs an asynchronous call with hedges. Cancelling the returned future cancels every attempt in flight
     * and the pending delay.
     *
     * @param call one attempt: the next layer; called once per attempt
     * @param <T> the result type
     * @return a future that completes with the first successful attempt, or the last failure
     */
    public <T> CompletableFuture<T> executeAsync(AsyncCall<T> call) {
        return executeAsync(call, null, null);
    }

    /**
     * Same as {@link #executeAsync(AsyncCall)}, and when the call fails, runs the fallback with the number
     * of attempts started.
     *
     * @param call one attempt: the next layer; called once per attempt
     * @param fallback decides which failures get the fallback; {@code null} for none
     * @param recovery the generated fallback; {@code null} for none
     * @param <T> the result type
     * @return a future that completes with the first successful attempt, the fallback's result, or the last failure
     */
    // java:S2589: the stage's class is checked before the cast, as in TimeoutPolicy.
    @SuppressWarnings({"unchecked", "java:S2589"})
    public <T> CompletableFuture<T> executeAsync(AsyncCall<T> call, @Nullable FallbackPolicy fallback,
            @Nullable Recovery<T> recovery) {
        if (!enabled) {
            return once(call, fallback, recovery);
        }
        RetryBudget hedgeBudget = this.budget;
        if (hedgeBudget != null) {
            hedgeBudget.onCall();
        }
        CompletionStage<T> stage = AsyncSupport.call(call);
        // Exact classes only, as in TimeoutPolicy: a first attempt that already succeeded needs no hedge.
        Class<?> type = stage.getClass();
        if ((type == CompletableFuture.class || type == LinkedFuture.class)
                && ((CompletableFuture<T>) stage).state() == Future.State.SUCCESS) {
            return CompletableFuture.completedFuture(((CompletableFuture<T>) stage).resultNow());
        }
        HedgeCall<T> hedge = new HedgeCall<>(this, call, fallback, recovery, stage);
        hedge.start();
        return hedge.result;
    }

    /** The hedge is disabled: one attempt, and the fallback if it fails. */
    private static <T> CompletableFuture<T> once(AsyncCall<T> call, @Nullable FallbackPolicy fallback,
            @Nullable Recovery<T> recovery) {
        CompletionStage<T> stage = AsyncSupport.call(call);
        LinkedFuture<T> result = new LinkedFuture<>(stage);
        stage.whenComplete((value, failure) -> {
            if (failure == null) {
                result.complete(value);
            } else if (fallback != null && recovery != null) {
                fallback.recoverOrFail(AsyncSupport.unwrap(failure), 1, recovery, result);
            } else {
                result.fail(AsyncSupport.unwrap(failure));
            }
        });
        return result;
    }

    /**
     * Completes a losing attempt's stage with a new, stackless {@link HedgeCancelledException} (one per loser, so
     * that no two stages share a mutable exception), which reaches the policies inside it. Runs only after a
     * hedge started or the call ended early: never on the path where the first attempt simply wins.
     */
    void cancelAttempt(Object stage) {
        try {
            if (stage instanceof CompletableFuture<?> future) {
                future.completeExceptionally(new HedgeCancelledException(id)); // a LinkedFuture forwards it inside
            } else if (stage instanceof Future<?> future) {
                future.cancel(true);
            }
        } catch (UnsupportedOperationException refused) {
            // minimalCompletionStage() and the like cannot be cancelled: the attempt runs on, ignored
        }
    }

    private long now() {
        return ticker.getAsLong();
    }

    private void publishHedged(int attempt, HedgeEvent.Reason reason) {
        EventSink<HedgeEvent.Hedged> sink = hedged;
        if (sink != null) {
            sink.publish(new HedgeEvent.Hedged(id, now(), attempt, reason));
        }
    }

    private void publishWon(int attempt) {
        EventSink<HedgeEvent.Won> sink = won;
        if (sink != null) {
            sink.publish(new HedgeEvent.Won(id, now(), attempt));
        }
    }

    private void publishSkipped(int attempt, HedgeEvent.SkipReason reason) {
        EventSink<HedgeEvent.Skipped> sink = skipped;
        if (sink != null) {
            sink.publish(new HedgeEvent.Skipped(id, now(), attempt, reason));
        }
    }

    private void publishFailed(int attempts, Throwable failure) {
        EventSink<HedgeEvent.Failed> sink = failed;
        if (sink != null) {
            sink.publish(new HedgeEvent.Failed(id, now(), attempts, failure));
        }
    }

    /** What a failure of an attempt means for the call. */
    enum Verdict {
        /** In {@code hedgeOn}: start the next attempt now. */
        HEDGE,
        /** A rejection by a policy inside the hedge: start no further attempt, keep those in flight. */
        REJECTED,
        /** In {@code abortOn}, or not in {@code hedgeOn}: the call fails now. */
        ABORT
    }

    /** Caches the {@link Verdict} per exception class. */
    private static final class Classification extends ClassValue<Verdict> {
        private final List<Class<? extends Throwable>> hedgeOn;
        private final List<Class<? extends Throwable>> abortOn;

        Classification(List<Class<? extends Throwable>> hedgeOn, List<Class<? extends Throwable>> abortOn) {
            this.hedgeOn = hedgeOn;
            this.abortOn = abortOn;
        }

        @Override
        protected Verdict computeValue(Class<?> type) {
            for (Class<? extends Throwable> abort : abortOn) {
                if (abort.isAssignableFrom(type)) {
                    return Verdict.ABORT;
                }
            }
            if (CallRejectedException.class.isAssignableFrom(type)) {
                return Verdict.REJECTED;
            }
            for (Class<? extends Throwable> hedge : hedgeOn) {
                if (hedge.isAssignableFrom(type)) {
                    return Verdict.HEDGE;
                }
            }
            return Verdict.ABORT;
        }
    }

    /** The verdict for one failure; for tests. */
    Verdict verdictForTest(Throwable failure) {
        return verdicts.get(failure.getClass());
    }

    /**
     * The state of one hedged call: the timer entry of its first delay, the callback of its first attempt, and
     * the link of its future.
     */
    static final class HedgeCall<T> extends TimerEntry implements BiConsumer<T, Throwable>, LinkedFuture.OnCancel {
        private final HedgePolicy policy;
        private final AsyncCall<T> call;
        private final @Nullable FallbackPolicy fallback;
        private final @Nullable Recovery<T> recovery;
        private final CompletionStage<T> first;
        final LinkedFuture<T> result;
        /** Attempts launched (bits 0-3), attempts failed (bits 4-7), SETTLED, STOPPED. */
        @SuppressWarnings({"unused", "FieldMayBeFinal"}) // accessed through PROGRESS
        private volatile int progress = 1;
        /** The stages of attempts 2 and later, by attempt index; created by the first hedge. */
        @SuppressWarnings({"unused", "java:S3077"}) // accessed through STAGES; its slots through SLOT
        private volatile Object @Nullable [] stages;
        /** The pending delay: this entry, a {@link Next}, {@code null} for none, or {@link #DONE}. */
        @SuppressWarnings({"unused", "java:S3077"}) // accessed through TIMER
        private volatile @Nullable Object timer;

        HedgeCall(HedgePolicy policy, AsyncCall<T> call, @Nullable FallbackPolicy fallback,
                @Nullable Recovery<T> recovery, CompletionStage<T> first) {
            this.policy = policy;
            this.call = call;
            this.fallback = fallback;
            this.recovery = recovery;
            this.first = first;
            this.result = new LinkedFuture<>(this);
        }

        /** Arms the first delay (or starts every attempt, for {@code delay = 0}), then watches attempt 1. */
        void start() {
            HedgePolicy hedge = policy;
            TimeoutTimer.Lane delays = hedge.lane;
            if (delays != null) {
                TIMER.setRelease(this, this); // before the push: the timer task may fire on another thread
                delays.timer.schedule(this, delays, hedge.ticker.getAsLong());
            } else if (hedge.delayNanos > 0) {
                arm(1);
            } else {
                int launched = 1; // every attempt at once, on the caller's thread
                while (launched < hedge.maxAttempts && launch(HedgeEvent.Reason.DELAY, launched)) {
                    launched++;
                }
            }
            first.whenComplete(this);
        }

        /** Attempt 1 completed. */
        @Override
        public void accept(@Nullable T value, @Nullable Throwable failure) {
            completed(0, value, failure);
        }

        /** The first delay passed (production timer); runs on a new virtual thread. */
        @Override
        public void run() {
            launch(HedgeEvent.Reason.DELAY, 1);
        }

        /** The caller cancelled the call's future: settle, and cancel the delay and every attempt. */
        @Override
        public void cancelled() {
            int seen = (int) PROGRESS.getAcquire(this);
            while ((seen & SETTLED) == 0) {
                int witness = (int) PROGRESS.compareAndExchange(this, seen, seen | SETTLED);
                if (witness == seen) {
                    cancelTimer();
                    cancelOthers(-1);
                    return;
                }
                seen = witness;
            }
        }

        void completed(int index, @Nullable T value, @Nullable Throwable failure) {
            if (failure == null) {
                succeeded(index, value);
            } else {
                failed(index, AsyncSupport.unwrap(failure));
            }
        }

        private void succeeded(int index, @Nullable T value) {
            int seen = (int) PROGRESS.getAcquire(this);
            while (true) {
                if ((seen & SETTLED) != 0) {
                    return; // another attempt won, or the call ended: a late success is dropped
                }
                int witness = (int) PROGRESS.compareAndExchange(this, seen, seen | SETTLED);
                if (witness == seen) {
                    break;
                }
                seen = witness;
            }
            cancelTimer();
            if (index > 0 || (seen & LAUNCHED) > 1) {
                hedgeWon(index);
            }
            result.complete(value);
        }

        /** Cold path: more than one attempt was launched; cancel the losers, and publish a hedge's win. */
        private void hedgeWon(int index) {
            cancelOthers(index);
            if (index > 0) {
                policy.publishWon(index + 1);
            }
        }

        private void failed(int index, Throwable cause) {
            Verdict verdict = policy.verdicts.get(cause.getClass());
            int seen = (int) PROGRESS.getAcquire(this);
            int next;
            while (true) {
                if ((seen & SETTLED) != 0) {
                    return; // the call has its outcome already: a late failure is dropped
                }
                next = (seen + FAILED_ONE) | (verdict == Verdict.ABORT ? SETTLED : 0);
                int witness = (int) PROGRESS.compareAndExchange(this, seen, next);
                if (witness == seen) {
                    break;
                }
                seen = witness;
            }
            if (verdict == Verdict.ABORT) {
                int launched = next & LAUNCHED;
                if (launched < policy.maxAttempts && (next & STOPPED) == 0) {
                    policy.publishSkipped(launched + 1, HedgeEvent.SkipReason.ABORTED);
                }
                finish(cause, launched);
                return;
            }
            if (verdict == Verdict.REJECTED) {
                stop(HedgeEvent.SkipReason.REJECTED);
            } else if ((next & (SETTLED | STOPPED)) == 0 && (next & LAUNCHED) < policy.maxAttempts) {
                launchSoon(cause);
                return;
            }
            settleIfAllFailed(cause);
        }

        /**
         * Starts the next attempt after a failure, but not on this thread: the thread that completed the failed
         * attempt may be an I/O thread of the caller's client. Like an async retry with no delay, the attempt
         * starts on a new virtual thread; on a manual ticker {@link PolicyEnvironment#later} schedules it with
         * no delay instead, so it starts on the thread that next advances the ticker (deterministic tests). If
         * no attempt can start by then (settled, stopped, budget), the call ends with {@code cause} if every
         * attempt has failed.
         */
        private void launchSoon(Throwable cause) {
            Runnable next = () -> {
                if (!launch(HedgeEvent.Reason.FAILURE, -1)) {
                    settleIfAllFailed(cause);
                }
            };
            policy.environment.later(0, next);
        }

        /** Ends the call with {@code cause} if every attempt launched has failed and no other can start. */
        private void settleIfAllFailed(Throwable cause) {
            int seen = (int) PROGRESS.getAcquire(this);
            while (true) {
                int launched = seen & LAUNCHED;
                if ((seen & SETTLED) != 0 || ((seen >>> FAILED_SHIFT) & LAUNCHED) != launched
                        || ((seen & STOPPED) == 0 && launched < policy.maxAttempts)) {
                    return; // settled already, or attempts are still in flight (their outcome decides)
                }
                int witness = (int) PROGRESS.compareAndExchange(this, seen, seen | SETTLED);
                if (witness == seen) {
                    finish(cause, launched);
                    return;
                }
                seen = witness;
            }
        }

        /**
         * The call failed with {@code cause} after {@code attempts} attempts: cancel the rest, then fail or fall
         * back.
         */
        private void finish(Throwable cause, int attempts) {
            cancelTimer();
            cancelOthers(-1);
            policy.publishFailed(attempts, cause);
            FallbackPolicy fallbackPolicy = fallback;
            Recovery<T> recoveryCall = recovery;
            if (fallbackPolicy != null && recoveryCall != null) {
                fallbackPolicy.recoverOrFail(cause, attempts, recoveryCall, result);
            } else {
                result.fail(cause);
            }
        }

        /** No further attempt may start. Publishes {@code Skipped} once, if an attempt could still have started. */
        private void stop(HedgeEvent.SkipReason reason) {
            int seen = (int) PROGRESS.getAcquire(this);
            while ((seen & (SETTLED | STOPPED)) == 0) {
                int witness = (int) PROGRESS.compareAndExchange(this, seen, seen | STOPPED);
                if (witness == seen) {
                    int launched = seen & LAUNCHED;
                    if (launched < policy.maxAttempts) {
                        policy.publishSkipped(launched + 1, reason);
                    }
                    return;
                }
                seen = witness;
            }
        }

        /**
         * Starts the next attempt if the call is still open, below {@code maxAttempts}, not stopped, and the
         * budget allows it. {@code armedFor} is the number of attempts launched when the delay that calls this
         * was armed ({@code -1} for a failure): a delay armed before a later launch is stale and starts nothing.
         *
         * @return {@code true} if an attempt started
         */
        boolean launch(HedgeEvent.Reason reason, int armedFor) {
            HedgePolicy hedge = policy;
            boolean budgetTaken = false;
            int seen = (int) PROGRESS.getAcquire(this);
            while (true) {
                int launched = seen & LAUNCHED;
                if ((seen & (SETTLED | STOPPED)) != 0 || launched >= hedge.maxAttempts
                        || (armedFor >= 0 && launched != armedFor)) {
                    return false; // a budget taken here is lost, which only lowers the budget
                }
                if (!budgetTaken) {
                    RetryBudget hedgeBudget = hedge.budget;
                    if (hedgeBudget != null && !hedgeBudget.tryAcquire(hedge.now())) {
                        stop(HedgeEvent.SkipReason.BUDGET_EXHAUSTED);
                        return false;
                    }
                    budgetTaken = true;
                }
                Object[] slots = slots();
                // Volatile: the settler reads stages after its own CAS on progress, so if this CAS comes first,
                // the settler sees the array (created before it) and its slot.
                int witness = (int) PROGRESS.compareAndExchange(this, seen, seen + 1);
                if (witness == seen) {
                    startAttempt(slots, launched, reason);
                    return true;
                }
                seen = witness;
            }
        }

        /** The slots of attempts 2 and later, created by the first launcher. */
        private Object[] slots() {
            Object[] slots = (Object[]) STAGES.getAcquire(this);
            if (slots != null) {
                return slots;
            }
            Object[] created = new Object[policy.maxAttempts];
            Object[] witness = (Object[]) STAGES.compareAndExchange(this, (Object[]) null, created);
            return witness == null ? created : witness;
        }

        private void startAttempt(Object[] slots, int index, HedgeEvent.Reason reason) {
            HedgePolicy hedge = policy;
            hedge.publishHedged(index + 1, reason);
            CompletionStage<T> stage = AsyncSupport.call(call);
            if (SLOT.compareAndExchange(slots, index, (Object) null, (Object) stage) != null) {
                hedge.cancelAttempt(stage); // the call settled while this attempt was starting
                return;
            }
            int launched = index + 1;
            if (launched < hedge.maxAttempts && hedge.delayNanos > 0) {
                arm(launched);
            } else {
                replaceTimer(null, launched); // no further delay: an older one is stale
            }
            stage.whenComplete(new Attempt<>(this, index));
        }

        /** Arms a delay that starts the attempt after {@code launched}, unless another starts it first. */
        private void arm(int launched) {
            HedgePolicy hedge = policy;
            Next next = new Next(this, launched);
            TimeoutTimer.Lane delays = hedge.lane;
            if (delays != null) {
                if (replaceTimer(next, launched)) {
                    delays.timer.schedule(next, delays, hedge.ticker.getAsLong());
                }
                return;
            }
            // Manual ticker: the entry never enters a lane, so it stays NEW until it fires or is cancelled.
            if (replaceTimer(next, launched)) {
                next.task = hedge.environment.later(hedge.delayNanos, next::fireOnTicker);
                if (next.state() == CANCELLED) {
                    next.cancelTask(); // cancelled before the task was set: take it off the ticker
                }
            }
        }

        /**
         * Makes {@code next} (armed for {@code launched}) the pending delay, cancelling an older one; does
         * nothing and returns {@code false} if the call settled or a newer delay is pending.
         */
        private boolean replaceTimer(@Nullable Next next, int launched) {
            Object current = TIMER.getAcquire(this);
            while (true) {
                if (current == DONE || (current != null && armedFor(current) >= launched)) {
                    return false;
                }
                Object witness = TIMER.compareAndExchange(this, current, next);
                if (witness == current) {
                    if (current != null) {
                        cancelTimer(current);
                    }
                    return true;
                }
                current = witness;
            }
        }

        private int armedFor(Object pending) {
            return pending == this ? 1 : ((Next) pending).armedFor;
        }

        /** The call settled: no delay may start anything any more. */
        private void cancelTimer() {
            Object pending = TIMER.getAndSet(this, DONE);
            if (pending != null && pending != DONE) {
                cancelTimer(pending);
            }
        }

        private static void cancelTimer(Object pending) {
            if (pending instanceof Next next) {
                next.cancel();
                next.cancelTask();
            } else {
                ((TimerEntry) pending).cancel();
            }
        }

        /** Cancels every attempt but {@code winner} ({@code -1}: all of them). */
        private void cancelOthers(int winner) {
            HedgePolicy hedge = policy;
            if (winner != 0) {
                hedge.cancelAttempt(first);
            }
            Object[] slots = (Object[]) STAGES.getAcquire(this);
            if (slots == null) {
                return;
            }
            for (int i = 1; i < slots.length; i++) {
                if (i != winner) {
                    Object stage = SLOT.getAndSet(slots, i, DONE);
                    if (stage != null && stage != DONE) {
                        hedge.cancelAttempt(stage);
                    }
                }
            }
        }

        /** Progress, for tests: launched, failed and the flags. */
        int progressForTest() {
            return (int) PROGRESS.getVolatile(this);
        }
    }

    /** The callback of attempt 2 or later. */
    private static final class Attempt<T> implements BiConsumer<T, Throwable> {
        private final HedgeCall<T> call;
        private final int index;

        Attempt(HedgeCall<T> call, int index) {
            this.call = call;
            this.index = index;
        }

        @Override
        public void accept(@Nullable T value, @Nullable Throwable failure) {
            call.completed(index, value, failure);
        }
    }

    /** A later delay: armed after attempt {@code armedFor} started. */
    private static final class Next extends TimerEntry {
        private final HedgeCall<?> call;
        final int armedFor;
        /** The ticker's task, on a manual ticker. */
        volatile @Nullable Future<?> task;

        Next(HedgeCall<?> call, int armedFor) {
            this.call = call;
            this.armedFor = armedFor;
        }

        /** The delay passed (production timer); runs on a new virtual thread. */
        @Override
        public void run() {
            call.launch(HedgeEvent.Reason.DELAY, armedFor);
        }

        /** The delay passed on a manual ticker; runs on the thread that advanced it. */
        void fireOnTicker() {
            if (tryMove(NEW, FIRED)) {
                run();
            }
        }

        /** Takes a cancelled delay off a manual ticker, so that {@code ManualClock.pendingTasks()} stays exact. */
        void cancelTask() {
            Future<?> scheduled = task;
            if (scheduled != null) {
                scheduled.cancel(false);
            }
        }
    }

    /** Test seam: a call started as {@link #executeAsync} would, without the fast path. */
    <T> HedgeCall<T> startForTest(AsyncCall<T> call, CompletionStage<T> first) {
        HedgeCall<T> hedge = new HedgeCall<>(this, call, null, null, first);
        hedge.start();
        return hedge;
    }
}
