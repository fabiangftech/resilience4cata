package dev.resilience4cata.generated;

import dev.resilience4cata.BulkheadConfig;
import dev.resilience4cata.BulkheadFullException;
import dev.resilience4cata.BulkheadHandle;
import dev.resilience4cata.BulkheadSnapshot;
import dev.resilience4cata.event.BulkheadEvent;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * Bulkhead for generated code: limits how many calls run at the same time.
 *
 * <pre>{@code
 * if (!bulkhead.tryEnter()) throw bulkhead.rejection();
 * try {
 *     return delegate.call();
 * } finally {
 *     bulkhead.exit();
 * }
 * }</pre>
 *
 * <p>Without waiting ({@code maxWait = 0}), slots are counted with a compare-and-set loop on one
 * counter. A full bulkhead only reads that counter, so rejections under overload do not add
 * contention. The counter lives in its own padded object, so that the writes of every call do not
 * share a cache line with the configuration fields that every call reads. With waiting, a non-fair
 * {@link BulkheadSemaphore} is used instead: the same counter and atomics when a slot is free, and a queue
 * that parks waiting threads (virtual threads included, without pinning them).
 *
 * <p>Asynchronous calls ({@link #executeAsync}) never block while waiting: they queue in
 * {@link BulkheadWaiters} and receive a released slot directly, oldest first. Synchronous and
 * asynchronous callers share the same slots. When both kinds wait, released slots alternate between
 * the oldest asynchronous waiter and the synchronous callers blocked on the semaphore; a new
 * synchronous caller can still take a free permit ahead of everyone (the semaphore is non-fair), while
 * a new asynchronous caller queues behind the asynchronous callers already waiting.
 *
 * <p>With a manual {@link dev.resilience4cata.Ticker} (a test clock), synchronous waits follow the
 * ticker instead of real time: a blocked caller gets a slot as soon as one is released and is rejected
 * when the ticker passes {@code maxWait}, never before (see {@link TickerWait}). It waits for as long as
 * the ticker does not move.
 */
public final class BulkheadPolicy implements BulkheadHandle {

    private final String id;
    private final BulkheadConfig config;
    private final boolean enabled;
    private final int maxConcurrent;
    private final long maxWaitNanos;
    /** Whether synchronous waits are measured on a manual {@link dev.resilience4cata.Ticker} instead of real time. */
    private final boolean waitsOnTicker;
    private final PaddedCounter inUse = new PaddedCounter();
    private final @Nullable BulkheadSemaphore semaphore;
    private final @Nullable BulkheadWaiters waiters;
    private final BulkheadFullException rejection;
    private final PolicyEnvironment environment;
    private final @Nullable EventSink<BulkheadEvent.Rejected> rejected;

    /**
     * Creates a bulkhead.
     *
     * @param id the bulkhead's id, used in the rejection message
     * @param config the configuration
     */
    BulkheadPolicy(String id, BulkheadConfig config) {
        this(id, config, PolicyEnvironment.DEFAULT);
    }

    /**
     * Creates a bulkhead.
     *
     * @param id the bulkhead's id, used in the rejection message
     * @param config the configuration
     * @param environment the timer for the wait limit of queued asynchronous calls
     */
    @dev.resilience4cata.annotation.Internal
    public BulkheadPolicy(String id, BulkheadConfig config, PolicyEnvironment environment) {
        this.id = id;
        this.environment = environment;
        this.config = config;
        this.enabled = config.enabled();
        this.maxConcurrent = config.maxConcurrent();
        this.maxWaitNanos = config.maxWait().toNanos();
        this.waitsOnTicker = environment.manual != null && maxWaitNanos > 0;
        this.semaphore = maxWaitNanos > 0 ? new BulkheadSemaphore(maxConcurrent) : null;
        this.waiters = maxWaitNanos > 0 ? new BulkheadWaiters() : null;
        this.rejection = new BulkheadFullException(id);
        this.rejected = environment.events.sink(BulkheadEvent.Rejected.class, id);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public BulkheadConfig config() {
        return config;
    }

    @Override
    public int concurrentCalls() {
        BulkheadSemaphore waiting = semaphore;
        return waiting != null ? maxConcurrent - waiting.availablePermits() : inUse.get();
    }

    @Override
    public int waitingCalls() {
        BulkheadSemaphore waiting = semaphore;
        return waiting != null ? queuedCalls() + waiting.getQueueLength() : 0;
    }

    @Override
    public BulkheadSnapshot snapshot() {
        return new BulkheadSnapshot(id, Math.clamp(concurrentCalls(), 0, maxConcurrent), Math.max(waitingCalls(), 0),
                maxConcurrent);
    }

    /** Returns the preallocated exception to throw when {@link #tryEnter()} returns {@code false}. */
    public BulkheadFullException rejection() {
        return rejection;
    }

    /**
     * Takes a slot, waiting up to {@code maxWait} if the bulkhead is full.
     *
     * @return {@code true} if the call may run; it must then call {@link #exit()}. {@code false} if the
     *     bulkhead stayed full, or the thread was interrupted while waiting (the interrupt flag is set
     *     again).
     */
    public boolean tryEnter() {
        if (!enabled) {
            return true;
        }
        BulkheadSemaphore waiting = semaphore;
        if (waiting == null) {
            return inUse.tryIncrement(maxConcurrent) || rejected();
        }
        if (waiting.tryAcquire()) {
            return true;
        }
        if (waitsOnTicker) {
            return awaitOnTicker(waiting);
        }
        try {
            return waiting.tryAcquire(maxWaitNanos, TimeUnit.NANOSECONDS) || rejected();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return rejected();
        }
    }

    /**
     * Waits for a slot with the wait limit measured on the registry's manual ticker: the thread blocks on
     * the semaphore without a time limit, so it is queued there like any synchronous waiter (and every
     * release, hand-off rule and {@link #waitingCalls()} sees it), and a {@link TickerWait} scheduled on
     * the ticker ends the wait when the ticker passes {@code maxWait}. Not used without a manual ticker.
     */
    private boolean awaitOnTicker(BulkheadSemaphore waiting) {
        TickerWait wait = new TickerWait(Thread.currentThread(), waiting);
        Future<?> deadline = environment.later(maxWaitNanos, wait);
        boolean acquired;
        try {
            waiting.acquire();
            acquired = true;
        } catch (@SuppressWarnings("java:S2142") InterruptedException e) {
            acquired = false; // afterTickerWait sets the interrupt flag again unless the deadline sent it
        }
        return afterTickerWait(wait, deadline, acquired);
    }

    /**
     * Ends a {@link #awaitOnTicker} wait, whether the semaphore was {@code acquired} or the wait was
     * interrupted. If the deadline has not fired, it never will: a permit taken is kept, and an
     * interrupt is the caller's, so it is a rejection with the flag set again, as without a ticker. If
     * the deadline fired, its interrupt is cleared, and a permit this thread acquired is kept: the
     * semaphore gave it while the thread was still queued, typically because a slot was released just
     * before the deadline and the thread had not run yet. If the deadline also took a permit for this
     * call ({@code GRANTED}), that one is one too many and is released. Otherwise the deadline's
     * decision stands.
     */
    boolean afterTickerWait(TickerWait wait, @Nullable Future<?> deadline, boolean acquired) {
        int outcome = wait.finish();
        if (outcome == TickerWait.DONE) {
            if (deadline != null) {
                deadline.cancel(false);
            }
            if (acquired) {
                return true;
            }
            Thread.currentThread().interrupt();
            return rejected();
        }
        if (acquired) {
            if (outcome == TickerWait.GRANTED) {
                exit();
            }
            return true;
        }
        return outcome == TickerWait.GRANTED || rejected();
    }

    /**
     * The deadline of one synchronous wait on a manual ticker; its {@link #run()} runs on the thread
     * that advances the ticker past {@code maxWait}. The waiting thread and the deadline race on one
     * state, set once from {@code WAITING}: to {@code DONE} by the waiting thread when it got a permit
     * or was interrupted by someone else first, or to {@code DECIDING} by the deadline, which then takes
     * a permit for the waiter if one is free right now ({@code GRANTED}, so that a slot released before
     * the deadline is never missed because the waiter had not woken up yet) or gives up
     * ({@code TIMED_OUT}), and interrupts the waiter to wake it. The waiter then waits for the decision
     * and clears that interrupt, so it never leaks to the caller.
     */
    static final class TickerWait implements Runnable {

        private static final VarHandle STATE;

        static {
            try {
                STATE = MethodHandles.lookup().findVarHandle(TickerWait.class, "state", int.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        static final int WAITING = 0;
        static final int DONE = 1;
        static final int DECIDING = 2;
        static final int GRANTED = 3;
        static final int TIMED_OUT = 4;

        private final Thread thread;
        private final BulkheadSemaphore semaphore;

        // java:S1845: the field and its VarHandle share a name, the usual VarHandle naming convention.
        @SuppressWarnings({"unused", "java:S1845"}) // accessed through STATE
        private volatile int state;

        TickerWait(Thread thread, BulkheadSemaphore semaphore) {
            this.thread = thread;
            this.semaphore = semaphore;
        }

        /** The deadline: decides the wait's outcome unless the waiting thread finished first. */
        @Override
        public void run() {
            if (STATE.compareAndSet(this, WAITING, DECIDING)) {
                int decision = semaphore.tryAcquire() ? GRANTED : TIMED_OUT;
                thread.interrupt();
                STATE.setVolatile(this, decision);
            }
        }

        /**
         * Called by the waiting thread when its wait on the semaphore ends.
         *
         * @return {@code DONE} if the deadline had not fired; otherwise the deadline's decision,
         *     {@code GRANTED} or {@code TIMED_OUT}, with the deadline's interrupt cleared
         */
        int finish() {
            int witness = (int) STATE.compareAndExchange(this, WAITING, DONE);
            if (witness == WAITING) {
                return DONE;
            }
            while ((witness = (int) STATE.getVolatile(this)) == DECIDING) {
                Thread.onSpinWait();
            }
            Thread.interrupted(); // the deadline's interrupt (set before its decision was published)
            return witness;
        }
    }

    /**
     * Publishes {@link BulkheadEvent.Rejected} if someone listens. Without listeners this is one null
     * check: no allocation, no clock read.
     *
     * @return {@code false}, so that rejecting paths can end with {@code return rejected();}
     */
    boolean rejected() {
        EventSink<BulkheadEvent.Rejected> sink = rejected;
        if (sink != null) {
            sink.publish(new BulkheadEvent.Rejected(id, environment.ticker.getAsLong(), maxConcurrent));
        }
        return false;
    }

    /**
     * Frees the slot taken by a successful {@link #tryEnter()}.
     *
     * <p>With {@code maxWait > 0}, the permit is released first and the number of asynchronous waiters
     * read afterwards; an enqueuing waiter does the opposite (counts itself, then tries to take a
     * permit). Both are volatile accesses, so at least one side sees the other and a slot is never
     * left free while a waiter stays queued. The read of the counter adds no fence: the semaphore's
     * release already ends with a volatile read. When nobody waits asynchronously, that read is the
     * only extra work.
     */
    public void exit() {
        if (!enabled) {
            return;
        }
        BulkheadSemaphore waiting = semaphore;
        if (waiting == null) {
            inUse.decrement();
        } else {
            waiting.release();
            BulkheadWaiters queued = waiters;
            if (queued != null && queued.count() > 0) {
                handOff(waiting, queued);
            }
        }
    }

    /**
     * A slot was released while asynchronous callers wait. When synchronous callers are blocked on the
     * semaphore as well, every other release is left to them; otherwise the slot goes to the oldest
     * asynchronous waiter.
     */
    private void handOff(BulkheadSemaphore waiting, BulkheadWaiters queued) {
        if (waiting.hasQueuedThreads() && queued.yieldToSync()) {
            return;
        }
        drain(waiting, queued);
    }

    /**
     * Gives free permits to the oldest asynchronous waiters. A permit is taken from the semaphore before
     * a waiter is claimed, so if someone else (a blocked synchronous caller, a new caller, another
     * drain) takes it first, nothing is handed off and the waiters stay queued: whoever holds the slot
     * will run this again when it releases it. A permit taken while no waiter can be claimed is given
     * back; the queue is then checked again, since a waiter may have been queued in between and failed
     * to take the permit while it was held here.
     */
    private void drain(BulkheadSemaphore waiting, BulkheadWaiters queued) {
        while (queued.count() > 0) {
            if (!waiting.tryAcquire()) {
                return;
            }
            BulkheadWaiters.Waiter<?> waiter = queued.claimOldest();
            if (waiter != null) {
                grant(waiter);
                continue;
            }
            waiting.release();
            if (queued.isEmpty()) {
                return;
            }
            Thread.onSpinWait();
        }
    }

    /** Runs a waiter's call, which now holds a slot, on a new virtual thread: never on the releasing thread. */
    private <T> void grant(BulkheadWaiters.Waiter<T> waiter) {
        waiter.cancelTimer();
        // With the queued call's own context class loader, not the releasing call's (Threads).
        Threads.startVirtual("resilience4cata-bulkhead", waiter.contextLoader, () -> runGranted(waiter));
    }

    /** Number of asynchronous callers waiting for a slot; for tests. */
    int queuedCalls() {
        BulkheadWaiters queued = waiters;
        return queued != null ? queued.count() : 0;
    }

    /** Padding before the counter, so that it does not share a cache line with what precedes it in memory. */
    @SuppressWarnings("unused")
    private static class PadBefore {
        long p0;
        long p1;
        long p2;
        long p3;
        long p4;
        long p5;
        long p6;
        long p7;
    }

    // java:S1700: the holder of the padded value, which it names; the VarHandle VALUE follows the convention.
    @SuppressWarnings("java:S1700")
    private static class Value extends PadBefore {
        static final VarHandle VALUE;

        static {
            try {
                VALUE = MethodHandles.lookup().findVarHandle(Value.class, "value", int.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        // java:S1845: the field and its VarHandle share a name, the usual VarHandle naming convention.
        @SuppressWarnings({"unused", "java:S1845"}) // accessed through VALUE
        volatile int value;
    }

    /** Superclass fields are laid out first, so the counter sits between two blocks of padding. */
    @SuppressWarnings("unused")
    private static final class PaddedCounter extends Value {
        long q0;
        long q1;
        long q2;
        long q3;
        long q4;
        long q5;
        long q6;
        long q7;

        boolean tryIncrement(int max) {
            int current = (int) VALUE.getOpaque(this);
            while (current < max) {
                int witness = (int) VALUE.compareAndExchangeAcquire(this, current, current + 1);
                if (witness == current) {
                    return true;
                }
                current = witness;
            }
            return false; // full: nothing was written
        }

        void decrement() {
            VALUE.getAndAddRelease(this, -1);
        }

        int get() {
            return (int) VALUE.getVolatile(this);
        }
    }

    /**
     * Runs an asynchronous call in the bulkhead: the slot is held until the call's stage completes, so
     * the limit applies to calls in flight. A full bulkhead is a failed future.
     *
     * <p>With {@code maxWait > 0}, the wait never blocks: a call that finds the bulkhead full (or other
     * asynchronous callers already waiting, so that they are served in order) is queued and its future
     * returned at once. When a slot is released, by an asynchronous or a synchronous call, it is handed
     * directly to the oldest waiter, whose call then starts on a new virtual thread. A waiter that is
     * not served within {@code maxWait} fails with {@link #rejection()} and can no longer receive a slot.
     * Cancelling the returned future cancels the call's stage (its slot is freed when the stage ends, at
     * once for a {@code CompletableFuture}), or takes a waiting call out of the queue.
     *
     * @param call the next layer
     * @param <T> the result type
     * @return a future that completes with the call's outcome
     */
    public <T> CompletableFuture<T> executeAsync(AsyncCall<T> call) {
        BulkheadWaiters queued = waiters;
        BulkheadSemaphore waiting = semaphore;
        if (!enabled || queued == null || waiting == null) {
            if (!tryEnter()) {
                return CompletableFuture.failedFuture(rejection());
            }
            return run(call);
        }
        if (queued.count() == 0 && waiting.tryAcquire()) {
            return run(call);
        }
        BulkheadWaiters.Waiter<T> waiter = new BulkheadWaiters.Waiter<>(call, queued, this);
        queued.add(waiter);
        waiter.setTimer(environment.later(maxWaitNanos, waiter));
        drain(waiting, queued);
        return waiter.result;
    }

    /**
     * Test seam: the first step of a queued {@link #executeAsync}, without its timer and without the
     * drain that follows, so that tests can replay interleavings. Requires {@code maxWait > 0}.
     */
    <T> BulkheadWaiters.Waiter<T> enqueueForTest(AsyncCall<T> call) {
        BulkheadWaiters queued = Objects.requireNonNull(waiters);
        BulkheadWaiters.Waiter<T> waiter = new BulkheadWaiters.Waiter<>(call, queued, this);
        queued.add(waiter);
        return waiter;
    }

    /** Test seam: what a granted waiter runs on its new virtual thread, on the caller's thread. */
    <T> void runGrantedForTest(BulkheadWaiters.Waiter<T> waiter) {
        runGranted(waiter);
    }

    /** Test seam: the drain that ends a queued {@link #executeAsync}. */
    void drainForTest() {
        drain(Objects.requireNonNull(semaphore), Objects.requireNonNull(waiters));
    }

    /**
     * Test seam: the deadline of a synchronous wait on a manual ticker for {@code thread}, as
     * {@link #awaitOnTicker} creates it, without scheduling it. Requires {@code maxWait > 0}.
     */
    TickerWait tickerWaitForTest(Thread thread) {
        return new TickerWait(thread, Objects.requireNonNull(semaphore));
    }

    /** Test seam: takes a permit without waiting, as a blocked {@code acquire()} that returns does. */
    boolean tryAcquireForTest() {
        return Objects.requireNonNull(semaphore).tryAcquire();
    }

    /**
     * Calls the next layer holding a slot, and frees the slot when its stage completes. Cancelling the
     * returned future cancels the stage, which (for a {@code CompletableFuture}) completes it at once, so
     * the slot is freed by this same callback before the cancel returns: exactly once, whichever of the
     * cancel and the stage's own completion comes first.
     */
    // One method with its own callback, as before downward cancellation: splitting the callback into a shared
    // helper (as runGranted uses) cost one more inlining level, and stacked async methods then allocated one
    // more object per call because escape analysis no longer removed this future (AsyncBenchmark.*Stack).
    private <T> CompletableFuture<T> run(AsyncCall<T> call) {
        CompletionStage<T> stage = AsyncSupport.call(call);
        if (AsyncSupport.succeeded(stage)) { // done already: the slot is free at once
            exit();
            return AsyncSupport.copy(stage);
        }
        LinkedFuture<T> result = new LinkedFuture<>(stage);
        stage.whenComplete((value, failure) -> {
            exit();
            if (failure == null) {
                result.complete(value);
            } else {
                result.fail(AsyncSupport.unwrap(failure));
            }
        });
        return result;
    }

    /** A queued waiter got a slot; runs on a new virtual thread. A waiter cancelled meanwhile gives the slot back. */
    private <T> void runGranted(BulkheadWaiters.Waiter<T> waiter) {
        LinkedFuture<T> result = waiter.result;
        if (!result.link(LinkedFuture.STARTING)) {
            exit(); // cancelled after the hand-off claimed it: the call never starts
            return;
        }
        CompletionStage<T> stage = AsyncSupport.call(waiter.call);
        result.link(stage);
        holdUntilComplete(stage, result);
    }

    private <T> void holdUntilComplete(CompletionStage<T> stage, LinkedFuture<T> result) {
        stage.whenComplete((value, failure) -> {
            exit();
            if (failure == null) {
                result.complete(value);
            } else {
                result.fail(AsyncSupport.unwrap(failure));
            }
        });
    }
}
