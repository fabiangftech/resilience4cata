package dev.resilience4cata.generated;

import dev.resilience4cata.CallTimeoutException;
import dev.resilience4cata.TimeoutConfig;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledFuture;
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
    private final Watchdog watchdog;

    /**
     * Creates a timeout policy that uses the shared watchdog.
     *
     * @param id the policy's id, used in the exception message
     * @param config the configuration
     */
    public TimeoutPolicy(String id, TimeoutConfig config) {
        this(id, config, config.enabled() ? Watchdog.shared() : null);
    }

    TimeoutPolicy(String id, TimeoutConfig config, @Nullable Watchdog watchdog) {
        this.id = id;
        this.config = config;
        this.enabled = config.enabled();
        this.timeoutNanos = config.timeout().toNanos();
        this.watchdog = watchdog;
        if (watchdog != null) {
            watchdog.requireTimeout(timeoutNanos);
        }
    }

    /** Returns the configuration this policy was created from. */
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
        scope.slack = dog.tick();
        scope.timeoutNanos = timeoutNanos;
        long running = (++scope.calls << 2) | RUNNING;
        scope.running = running;
        STATE.setRelease(scope, running); // publishes start, slack and timeout to the watchdog
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
        if (STATE.compareAndSet(scope, running, call | IDLE)) {
            watchdog.scopes().pop();
            return;
        }
        // The watchdog got there first: wait until its interrupt has been sent, then clear it.
        while (((long) STATE.getVolatile(scope) & STATE_BITS) == INTERRUPTING) {
            Thread.onSpinWait();
        }
        Thread.interrupted();
        STATE.setRelease(scope, call | IDLE);
        watchdog.scopes().pop();
        throw new CallTimeoutException(id, Duration.ofNanos(timeoutNanos), failure);
    }

    /** One timed call in progress on one thread. Reused by that thread for later calls. */
    public static final class Scope {
        private final Thread thread;
        /** Call number in the high bits, state in the two low bits. */
        @SuppressWarnings("unused") // accessed through STATE
        private volatile long stamp = IDLE;
        // Written by the owner thread before the stamp is set to RUNNING (a release), read by the
        // watchdog after it reads RUNNING (an acquire).
        long start;
        long slack;
        long timeoutNanos;
        // Only touched by the owner thread.
        private long calls;
        private long running;

        Scope(Thread thread) {
            this.thread = thread;
        }

        /** Called by the watchdog: interrupts the thread if the call has run past its timeout. */
        void expireIfOverdue(long now) {
            expireIfOverdue(now, (long) STATE.getAcquire(this));
        }

        /** Test seam: decides as a watchdog that read the stamp {@code seen} earlier would. */
        void expireIfOverdue(long now, long seen) {
            if ((seen & STATE_BITS) == RUNNING && now - start >= timeoutNanos + slack) {
                long call = seen & ~STATE_BITS;
                // Fails if this call ended, or another call started, since the stamp was read.
                if (STATE.compareAndSet(this, seen, call | INTERRUPTING)) {
                    thread.interrupt();
                    STATE.setVolatile(this, call | TIMED_OUT);
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
     * is a {@link java.util.concurrent.Future}. The timer is the library's single async timer thread,
     * and the timeout is completed on a new virtual thread, never on the timer.
     *
     * @param call the next layer
     * @param <T> the result type
     * @return a future that completes with the call's outcome, or fails with {@link CallTimeoutException}
     */
    public <T> CompletableFuture<T> executeAsync(AsyncCall<T> call) {
        CompletionStage<T> stage = AsyncSupport.call(call);
        CompletableFuture<T> result = new CompletableFuture<>();
        if (!enabled) {
            AsyncSupport.relay(stage, result);
            return result;
        }
        ScheduledFuture<?> timer = AsyncSupport.later(timeoutNanos, () -> {
            if (result.completeExceptionally(new CallTimeoutException(id, config.timeout(), null))) {
                AsyncSupport.cancel(stage);
            }
        });
        stage.whenComplete((value, failure) -> {
            if (timer != null) {
                timer.cancel(false);
            }
            if (failure == null) {
                result.complete(value);
            } else {
                result.completeExceptionally(AsyncSupport.unwrap(failure));
            }
        });
        return result;
    }
}
