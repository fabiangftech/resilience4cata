package dev.resilience4cata.generated;

import dev.resilience4cata.CallTimeoutException;
import dev.resilience4cata.Ticker;
import dev.resilience4cata.TimeoutConfig;
import dev.resilience4cata.event.TimeoutEvent;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Future;
import java.util.function.BiConsumer;
import org.jspecify.annotations.Nullable;

/**
 * Timeout for generated code. The call runs on the caller's thread; the shared {@link Watchdog}
 * interrupts that thread if the call runs past its timeout.
 *
 * <pre>{@code
 * TimeoutPolicy.Scope scope = timeout.enter();
 * R result;
 * try {
 *     result = delegate.call();
 * } catch (Throwable e) {
 *     timeout.failed(scope, e);   // throws CallTimeoutException if the call had timed out
 *     throw e;
 * }
 * timeout.succeeded(scope);       // same
 * return result;
 * }</pre>
 *
 * <p>Each scope moves through {@code RUNNING → INTERRUPTING → TIMED_OUT} on the watchdog's side, or
 * {@code RUNNING → IDLE} on the caller's side, with compare-and-set. Scopes are reused by later calls
 * on the same thread, so the state is stamped with a call number: a watchdog that read an earlier
 * call's state cannot interrupt the next call by mistake. If the call ends while the
 * watchdog is interrupting it, the caller waits for {@code TIMED_OUT}, then clears the interrupt:
 * an interrupt sent by the watchdog never reaches later work. (An unrelated interrupt that arrives at
 * that same moment is cleared too.)
 */
public final class TimeoutPolicy {

    static final long IDLE = 0;
    static final long RUNNING = 1;
    static final long INTERRUPTING = 2;
    static final long TIMED_OUT = 3;
    private static final long STATE_BITS = 3;

    private static final VarHandle STATE;

    static {
        try {
            STATE = MethodHandles.lookup().findVarHandle(Scope.class, "stamp", long.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final String id;
    private final TimeoutConfig config;
    private final boolean enabled;
    private final long timeoutNanos;
    private final @Nullable Watchdog watchdog; // null when the timeout is disabled
    private final TimeoutTimer.@Nullable Lane lane;
    /** A manual ticker that schedules async deadlines instead of {@link #lane}; {@code null} in production. */
    private final @Nullable Ticker manual;
    private final java.util.function.LongSupplier ticker;
    private final @Nullable EventSink<TimeoutEvent.TimedOut> timedOut;
    private final PolicyEnvironment environment;

    /**
     * Creates a timeout policy that uses the shared watchdog.
     *
     * @param id the policy's id, used in the exception message
     * @param config the configuration
     */
    TimeoutPolicy(String id, TimeoutConfig config) {
        this(id, config, config.enabled() ? Watchdog.shared() : null);
    }

    /**
     * Creates a timeout policy.
     *
     * @param id the policy's id, used in the exception message
     * @param config the configuration
     * @param environment the watchdog and timer to use
     */
    @dev.resilience4cata.annotation.Internal
    public TimeoutPolicy(String id, TimeoutConfig config, PolicyEnvironment environment) {
        this(id, config, config.enabled() ? environment.watchdog() : null,
                config.enabled() ? environment.timeoutTimer() : null, config.enabled() ? environment.manual : null,
                environment);
    }

    TimeoutPolicy(String id, TimeoutConfig config, @Nullable Watchdog watchdog) {
        this(id, config, watchdog, config.enabled() ? TimeoutTimer.shared() : null);
    }

    TimeoutPolicy(String id, TimeoutConfig config, @Nullable Watchdog watchdog, @Nullable TimeoutTimer timer) {
        this(id, config, watchdog, timer, null, PolicyEnvironment.DEFAULT);
    }

    /** For tests: a manual watchdog and timer, and the listeners of {@code events}. */
    TimeoutPolicy(String id, TimeoutConfig config, @Nullable Watchdog watchdog, @Nullable TimeoutTimer timer,
            PolicyEnvironment events) {
        this(id, config, watchdog, timer, null, events);
    }

    private TimeoutPolicy(String id, TimeoutConfig config, @Nullable Watchdog watchdog, @Nullable TimeoutTimer timer,
            @Nullable Ticker manual, PolicyEnvironment environment) {
        this.id = id;
        this.environment = environment;
        this.ticker = environment.ticker;
        this.timedOut = environment.events.sink(TimeoutEvent.TimedOut.class, id);
        this.manual = manual;
        this.config = config;
        this.enabled = config.enabled();
        this.timeoutNanos = config.timeout().toNanos();
        this.watchdog = watchdog;
        if (watchdog != null) {
            watchdog.requireTimeout(timeoutNanos);
        }
        this.lane = timer == null ? null : timer.lane(timeoutNanos); // no thread starts until the first async call
    }

    /** Returns the configuration this policy was created from. */
    @dev.resilience4cata.annotation.Internal
    public TimeoutConfig config() {
        return config;
    }

    /**
     * Starts timing a call on the current thread.
     *
     * @return the scope to pass to {@link #succeeded} or {@link #failed}; {@code null} when disabled
     */
    public @Nullable Scope enter() {
        if (!enabled) {
            return null;
        }
        Watchdog dog = watchdog;
        Scope scope = dog.scopes().push();
        scope.start = dog.now();
        // The start can be up to one tick old, so allow one more tick: never early.
        scope.limit = timeoutNanos + dog.tick();
        long running = (++scope.calls << 2) | RUNNING;
        scope.running = running;
        STATE.setRelease(scope, running); // publishes start and limit to the watchdog
        return scope;
    }

    /**
     * Ends a call that returned normally.
     *
     * @param scope the value returned by {@link #enter()}
     * @throws CallTimeoutException if the call ran out of time
     */
    public void succeeded(@Nullable Scope scope) {
        if (scope != null) {
            end(scope, null);
        }
    }

    /**
     * Ends a call that threw. Returns normally if the call had not timed out; the caller then rethrows.
     *
     * @param scope the value returned by {@link #enter()}
     * @param failure what the call threw
     * @throws CallTimeoutException if the call ran out of time, with {@code failure} as its cause
     */
    public void failed(@Nullable Scope scope, Throwable failure) {
        if (scope != null) {
            end(scope, failure);
        }
    }

    private void end(Scope scope, @Nullable Throwable failure) {
        long running = scope.running;
        long call = running & ~STATE_BITS;
        // Acquire is enough for RUNNING -> IDLE; the release half of a full-fence CAS is not needed.
        // - Who wins is decided by atomicity alone: the watchdog interrupts only after its own CAS from
        //   this exact stamp (call number + RUNNING) succeeded, and both CASes act on one location, so
        //   exactly one succeeds. A watchdog that read an earlier call's stamp fails because the call
        //   number differs (ABA).
        // - Nothing before the CAS needs ordering: the watchdog reads only start and limit, which this
        //   call wrote before enter's setRelease.
        // - What comes after it does: the next call writes start and limit with plain stores. Without
        //   acquire (a release-only CAS), those stores could become visible before IDLE, so a watchdog
        //   still holding RUNNING of this call could read the next call's limit (shorter if that call
        //   uses a shorter timeout) with this call's start, and its CAS could still win: an early
        //   interrupt. With acquire, those stores are ordered after our CAS, and the watchdog reads
        //   limit before its own CAS, a volatile CAS and so a release. If the watchdog's CAS came first,
        //   our acquire CAS read the value it wrote and synchronizes with it, so the watchdog's read of
        //   limit happened before our later store and cannot have seen it. Hence a watchdog that saw
        //   the next call's values has its CAS after ours, and it fails.
        // - On failure (the watchdog won), the witness is read with acquire, and the wait loop below
        //   reads the stamp again with getAcquire, at least once: once it reads TIMED_OUT, written by
        //   the watchdog with setRelease after thread.interrupt(), the interrupt is visible and
        //   Thread.interrupted() clears it.
        long witness = (long) STATE.compareAndExchangeAcquire(scope, running, call | IDLE);
        if (witness == running) {
            scope.owner.pop();
            return;
        }
        timedOut(scope, call, failure);
    }

    /**
     * The watchdog got there first: wait until its interrupt has been sent, clear it, and throw. Kept
     * out of {@link #end} so that the per-call method stays small enough to inline.
     */
    private void timedOut(Scope scope, long call, @Nullable Throwable failure) {
        while (((long) STATE.getAcquire(scope) & STATE_BITS) == INTERRUPTING) {
            Thread.onSpinWait();
        }
        Thread.interrupted();
        STATE.setRelease(scope, call | IDLE);
        scope.owner.pop();
        publishTimedOut();
        throw new CallTimeoutException(id, Duration.ofNanos(timeoutNanos), failure);
    }

    /** Publishes {@link TimeoutEvent.TimedOut} if someone listens; reads the clock only then. */
    private void publishTimedOut() {
        EventSink<TimeoutEvent.TimedOut> sink = timedOut;
        if (sink != null) {
            sink.publish(new TimeoutEvent.TimedOut(id, ticker.getAsLong(), config.timeout()));
        }
    }

    /** One timed call in progress on one thread. Reused by that thread for later calls. */
    public static final class Scope {
        private final Thread thread;
        /** The stack this scope belongs to: popping through it saves a second ThreadLocal lookup per call. */
        final Watchdog.ThreadScopes owner;
        /** Call number in the high bits, state in the two low bits. */
        @SuppressWarnings("unused") // accessed through STATE
        private volatile long stamp = IDLE;
        // Written by the owner thread before the stamp is set to RUNNING (a release), read by the
        // watchdog after it reads RUNNING (an acquire).
        long start;
        /** Timeout plus one tick of slack for the coarse start. */
        long limit;
        // Only touched by the owner thread.
        private long calls;
        private long running;

        Scope(Watchdog.ThreadScopes owner) {
            this.owner = owner;
            this.thread = owner.thread;
        }

        /** Called by the watchdog: interrupts the thread if the call has run past its timeout. */
        void expireIfOverdue(long now) {
            expireIfOverdue(now, (long) STATE.getAcquire(this));
        }

        /** Test seam: decides as a watchdog that read the stamp {@code seen} earlier would. */
        void expireIfOverdue(long now, long seen) {
            if ((seen & STATE_BITS) == RUNNING && now - start >= limit) {
                long call = seen & ~STATE_BITS;
                // Fails if this call ended, or another call started, since the stamp was read.
                if (STATE.compareAndSet(this, seen, call | INTERRUPTING)) {
                    thread.interrupt();
                    // Release: the caller reads TIMED_OUT with an acquire, then sees the interrupt.
                    STATE.setRelease(this, call | TIMED_OUT);
                }
            }
        }

        /** The raw stamp; used by tests. */
        long stampForTest() {
            return (long) STATE.getVolatile(this);
        }

        /** The current state, without the call number; used by tests. */
        long state() {
            return (long) STATE.getVolatile(this) & STATE_BITS;
        }
    }

    /**
     * Runs an asynchronous call with a deadline. If its stage has not completed after the timeout, the
     * returned future fails with {@link CallTimeoutException} and the call's stage is cancelled when it
     * is a {@link java.util.concurrent.Future}. The deadline is kept by the library's lock-free
     * timer: never early, at most about two ticks late (a tick is a sixteenth of the
     * shortest async timeout, between 1 ms and 50 ms). The timeout is completed on a new virtual thread,
     * never on the timer thread. A stage that is already complete when the call returns needs no timer.
     * Cancelling the returned future cancels the deadline and the stage.
     *
     * @param call the next layer
     * @param <T> the result type
     * @return a future that completes with the call's outcome, or fails with {@link CallTimeoutException}
     */
    // java:S2589: time != null always holds there (checked above); kept so the nullable local is visibly non-null.
    @SuppressWarnings("java:S2589")
    public <T> CompletableFuture<T> executeAsync(AsyncCall<T> call) {
        CompletionStage<T> stage = AsyncSupport.call(call);
        TimeoutTimer.Lane deadlines = lane;
        Ticker time = manual;
        if (deadlines == null && time == null) {
            LinkedFuture<T> result = new LinkedFuture<>(stage);
            AsyncSupport.relay(stage, result);
            return result;
        }
        // Exact classes only: subclasses (such as minimal stages) may not support state(). A LinkedFuture is
        // the future of an inner policy (a bulkhead, say), which supports it like a plain one.
        Class<?> type = stage.getClass();
        if (type == CompletableFuture.class || type == LinkedFuture.class || type == Ended.class) {
            CompletableFuture<T> future = (CompletableFuture<T>) stage;
            Future.State state = future.state();
            if (state == Future.State.SUCCESS) {
                return AsyncSupport.copy(future);
            }
            if (state == Future.State.FAILED) {
                return CompletableFuture.failedFuture(AsyncSupport.unwrap(future.exceptionNow()));
            }
        }
        Expiry<T> expiry = new Expiry<>(this, stage);
        if (deadlines != null) {
            deadlines.timer.schedule(expiry, deadlines);
        } else if (time != null) {
            // Manual ticker: the expiry never enters a lane, so it stays NEW until it fires or is cancelled.
            Future<?> task = time.schedule(timeoutNanos, () -> {
                if (expiry.tryMove(TimerEntry.NEW, TimerEntry.FIRED)) {
                    expiry.run();
                }
            });
            // Take a call that finished in time off the ticker, so that ManualClock.pendingTasks() stays exact.
            stage.whenComplete((value, failure) -> task.cancel(false));
        }
        stage.whenComplete(expiry);
        return expiry.result;
    }

    /**
     * The asynchronous form for a streaming call (a method that returns a {@code Flux}): as
     * {@link #executeAsync(AsyncCall)}, but the deadline restarts on every element of the call's stream.
     * The call times out when nothing arrives for the timeout: no first element since the call started, no
     * next element (or end) since the previous one. Time spent waiting for the subscriber's demand counts,
     * as with Reactor's {@code timeout} operator.
     *
     * <p>An element costs one clock read and one plain write: it moves no timer. The first deadline is
     * kept like {@link #executeAsync(AsyncCall)}'s; when it passes after an element arrived, the remaining
     * time is waited on the library's precise timer (or the registry's ticker), once per timeout of
     * activity at most.
     *
     * @param call the next layer
     * @param elements the streaming call, told of this attempt's listener before {@code call} starts
     * @param <T> the result type
     * @return a future that completes with the call's outcome, or fails with {@link CallTimeoutException}
     */
    public <T> CompletableFuture<T> executeStreamAsync(AsyncCall<T> call, Elements elements) {
        TimeoutTimer.Lane deadlines = lane;
        Ticker time = manual;
        if (deadlines == null && time == null) {
            return executeAsync(call);
        }
        Idle<T> idle = new Idle<>(this, ticker.getAsLong());
        elements.listen(idle, true);
        CompletionStage<T> stage = AsyncSupport.call(call);
        Class<?> type = stage.getClass();
        if (type == CompletableFuture.class || type == LinkedFuture.class || type == Ended.class) {
            CompletableFuture<T> future = (CompletableFuture<T>) stage;
            Future.State state = future.state();
            if (state == Future.State.SUCCESS) {
                return AsyncSupport.copy(future);
            }
            if (state == Future.State.FAILED) {
                return CompletableFuture.failedFuture(AsyncSupport.unwrap(future.exceptionNow()));
            }
        }
        idle.stage = stage;
        if (deadlines != null) {
            deadlines.timer.schedule(idle, deadlines);
        } else if (time != null) {
            idle.pending(time.schedule(timeoutNanos, () -> {
                if (idle.tryMove(TimerEntry.NEW, TimerEntry.FIRED)) {
                    idle.run();
                }
            }));
        }
        stage.whenComplete(idle);
        return idle.result;
    }

    /**
     * The deadline of one attempt of a streaming call, restarted by its elements. Elements only record
     * when they arrived; the deadline itself moves only when it passes: then, if an element arrived since,
     * the rest of the time is waited again (a re-check), else the call times out.
     */
    private static final class Idle<T> extends TimerEntry
            implements BiConsumer<T, Throwable>, LinkedFuture.OnCancel, ElementListener {

        private static final VarHandle LAST;
        private static final VarHandle PENDING;
        private static final Object DONE = new Object();

        static {
            try {
                MethodHandles.Lookup lookup = MethodHandles.lookup();
                LAST = lookup.findVarHandle(Idle.class, "last", long.class);
                PENDING = lookup.findVarHandle(Idle.class, "pending", Object.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        private final TimeoutPolicy policy;
        final LinkedFuture<T> result;
        @Nullable CompletionStage<T> stage; // set before the deadline is scheduled, which publishes it

        // java:S1845: the fields and their VarHandles share names, the usual VarHandle naming convention.
        @SuppressWarnings({"unused", "java:S1845"}) // accessed through LAST
        private volatile long last; // when the attempt started, then when its latest element arrived

        @SuppressWarnings({"unused", "java:S1845", "java:S3077"}) // accessed through PENDING
        private volatile @Nullable Object pending; // the scheduled re-check (a Future), or DONE

        Idle(TimeoutPolicy policy, long start) {
            this.policy = policy;
            this.result = new LinkedFuture<>(this);
            LAST.setOpaque(this, start);
        }

        /** An element arrived: one clock read and one plain write, read by the next check. */
        @Override
        public void element() {
            LAST.setOpaque(this, policy.ticker.getAsLong());
        }

        /** The first deadline passed; runs on a new virtual thread (or the thread advancing the ticker). */
        @Override
        public void run() {
            check();
        }

        private void check() {
            long remaining = (long) LAST.getAcquire(this) + policy.timeoutNanos - policy.ticker.getAsLong();
            if (remaining > 0) {
                recheckIn(remaining);
            } else if (result.fail(new CallTimeoutException(policy.id, policy.config.timeout(), null))) {
                policy.publishTimedOut(); // only when the timeout won against the stage's completion
                CompletionStage<T> attempt = stage;
                if (attempt != null) {
                    LinkedFuture.cancelInner(attempt);
                }
            }
        }

        private void recheckIn(long nanos) {
            Object current = PENDING.getAcquire(this);
            if (current == DONE) {
                return;
            }
            Future<?> next = policy.environment.later(nanos, this::check);
            if (next != null && !PENDING.compareAndSet(this, current, next)) {
                next.cancel(false); // the stage completed meanwhile
            }
        }

        void pending(Future<?> first) {
            if (!PENDING.compareAndSet(this, null, first)) {
                first.cancel(false);
            }
        }

        private void stop() {
            cancel();
            Object scheduled = PENDING.getAndSet(this, DONE);
            if (scheduled instanceof Future<?> future) {
                future.cancel(false); // so that a manual ticker's pendingTasks() and the timer's queue stay exact
            }
        }

        /** The stage completed. */
        @Override
        public void accept(@Nullable T value, @Nullable Throwable failure) {
            stop();
            if (failure == null) {
                result.complete(value);
            } else {
                result.fail(AsyncSupport.unwrap(failure));
            }
        }

        /** The caller cancelled the call's future: no deadline is needed any more, nor is the stage. */
        @Override
        public void cancelled() {
            stop();
            CompletionStage<T> attempt = stage;
            if (attempt != null) {
                LinkedFuture.cancelInner(attempt);
            }
        }
    }

    /**
     * The deadline of one async call; also the callback that cancels it when the stage completes, and the
     * link of the call's future, so that cancelling the future cancels the deadline and then the stage.
     */
    private static final class Expiry<T> extends TimerEntry
            implements BiConsumer<T, Throwable>, LinkedFuture.OnCancel {
        private final TimeoutPolicy policy;
        private final CompletionStage<T> stage;
        final LinkedFuture<T> result;

        Expiry(TimeoutPolicy policy, CompletionStage<T> stage) {
            this.policy = policy;
            this.stage = stage;
            this.result = new LinkedFuture<>(this);
        }

        /** The deadline passed; runs on a new virtual thread. */
        @Override
        public void run() {
            if (result.fail(new CallTimeoutException(policy.id, policy.config.timeout(), null))) {
                policy.publishTimedOut(); // only when the timeout won against the stage's completion
                LinkedFuture.cancelInner(stage);
            }
        }

        /** The stage completed. */
        @Override
        public void accept(@Nullable T value, @Nullable Throwable failure) {
            cancel();
            if (failure == null) {
                result.complete(value);
            } else {
                result.fail(AsyncSupport.unwrap(failure));
            }
        }

        /** The caller cancelled the call's future: the deadline is no longer needed, nor is the stage. */
        @Override
        public void cancelled() {
            cancel();
            LinkedFuture.cancelInner(stage);
        }
    }
}
