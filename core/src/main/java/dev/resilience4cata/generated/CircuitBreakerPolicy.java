package dev.resilience4cata.generated;

import dev.resilience4cata.CallNotPermittedException;
import dev.resilience4cata.CircuitBreakerConfig;
import dev.resilience4cata.CircuitBreakerState;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * Circuit breaker for generated code: lock-free, and allocation-free except on state transitions.
 *
 * <pre>{@code
 * long token = cb.acquire();
 * if (token < 0) throw cb.rejection();
 * long start = cb.startTimer();
 * try {
 *     R result = delegate.call();
 *     cb.onSuccess(token, start);
 *     return result;
 * } catch (Throwable e) {
 *     cb.onFailure(token, start, e);
 *     throw e;
 * }
 * }</pre>
 *
 * <p>The state is an immutable {@code Phase} object swapped with compare-and-set. Each phase has a
 * generation number, and {@link #acquire()} returns it as the token. Outcomes whose token belongs to
 * an older phase are dropped, so a call that started while closed cannot decide a half-open probe.
 *
 * <p><b>Quiet state.</b> While the breaker is closed and its window holds only fast successes,
 * another fast success changes nothing. The {@code quiet} field then holds the closed phase's
 * generation, and {@link #acquire()} and {@link #onSuccess} only read that one field: no phase or
 * window access, no writes. Ordering keeps it correct: a failure is counted in the window
 * <em>before</em> {@code quiet} is cleared, and a success sets {@code quiet} <em>before</em>
 * re-checking the window, so one of the two always sees the other. All other reads of
 * {@code phase} and {@code quiet} are opaque: every field they lead to is final, so the objects are
 * safely published without acquire barriers.
 */
public final class CircuitBreakerPolicy {

    /** Returned by {@link #acquire()} when the call is not permitted. */
    public static final long REJECTED = -1L;

    private static final long NOT_QUIET = -1L;
    private static final VarHandle PHASE;
    private static final VarHandle QUIET;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            PHASE = lookup.findVarHandle(CircuitBreakerPolicy.class, "phase", Phase.class);
            QUIET = lookup.findVarHandle(CircuitBreakerPolicy.class, "quiet", long.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final CircuitBreakerConfig config;
    private final boolean enabled;
    private final int windowSize;
    private final int minimumCalls;
    private final float failureRateThreshold;
    private final float slowCallRateThreshold;
    private final boolean timed;
    private final long slowCallNanos;
    private final long waitInOpenNanos;
    private final int halfOpenCalls;
    private final ClassValue<Outcome> classification;
    private final CallNotPermittedException rejection;
    private final LongSupplier ticker;

    @SuppressWarnings("unused") // accessed through PHASE
    private volatile Phase phase;
    @SuppressWarnings("unused") // accessed through QUIET
    private volatile long quiet = NOT_QUIET;

    /**
     * Creates a closed circuit breaker.
     *
     * @param instanceId id used in the rejection message
     * @param config the configuration
     */
    public CircuitBreakerPolicy(String instanceId, CircuitBreakerConfig config) {
        this(instanceId, config, System::nanoTime);
    }

    CircuitBreakerPolicy(String instanceId, CircuitBreakerConfig config, LongSupplier ticker) {
        this.config = config;
        this.enabled = config.enabled();
        this.windowSize = config.windowSize();
        this.minimumCalls = Math.min(config.minimumCalls(), config.windowSize());
        this.failureRateThreshold = config.failureRateThreshold();
        this.slowCallRateThreshold = config.slowCallRateThreshold();
        this.slowCallNanos = config.slowCallDuration().toNanos();
        this.timed = slowCallNanos > 0;
        this.waitInOpenNanos = config.waitInOpen().toNanos();
        this.halfOpenCalls = config.halfOpenCalls();
        this.classification = new Classification(config.recordOn(), config.ignoreOn());
        this.rejection = new CallNotPermittedException(instanceId);
        this.ticker = ticker;
        this.phase = new Closed(0, new CountWindow(windowSize, false));
    }

    /** Returns the configuration this breaker was created from. */
    public CircuitBreakerConfig config() {
        return config;
    }

    /** Returns the current state. */
    public CircuitBreakerState state() {
        return phase().state();
    }

    /** Returns the preallocated exception to throw when {@link #acquire()} returns {@link #REJECTED}. */
    public CallNotPermittedException rejection() {
        return rejection;
    }

    /**
     * Asks permission for one call.
     *
     * @return a token to pass to {@link #onSuccess} or {@link #onFailure}, or {@link #REJECTED}
     */
    public long acquire() {
        long quietToken = (long) QUIET.getOpaque(this);
        if (quietToken >= 0) {
            return quietToken;
        }
        Phase current = phase();
        if (current.kind == Phase.CLOSED || !enabled) {
            return current.generation;
        }
        return acquireSlow(current);
    }

    private long acquireSlow(Phase current) {
        while (true) {
            if (current instanceof Open open) {
                if (ticker.getAsLong() - open.openedAt < waitInOpenNanos) {
                    return REJECTED;
                }
                Phase halfOpen = new HalfOpen(open.generation + 1, halfOpenCalls);
                if (!PHASE.compareAndSet(this, current, halfOpen)) {
                    current = phase();
                    continue;
                }
                current = halfOpen;
            }
            if (current instanceof HalfOpen halfOpen) {
                // Never below zero: a rejected call must not use up a permit that an ignored probe gives back.
                AtomicInteger permits = halfOpen.permits;
                int left;
                do {
                    left = permits.get();
                    if (left <= 0) {
                        return REJECTED;
                    }
                } while (!permits.compareAndSet(left, left - 1));
                return halfOpen.generation;
            }
            return current.generation;
        }
    }

    /**
     * Starts timing a call, for slow-call detection.
     *
     * @return a timestamp to pass to {@link #onSuccess} or {@link #onFailure}; reads the clock only
     *     if slow-call detection is on
     */
    public long startTimer() {
        return timed ? ticker.getAsLong() : 0L;
    }

    /**
     * Records a call that returned normally.
     *
     * @param token the value returned by {@link #acquire()}
     * @param start the value returned by {@link #startTimer()}
     */
    public void onSuccess(long token, long start) {
        int slow = slowBit(start);
        if (slow == 0 && token == (long) QUIET.getOpaque(this)) {
            return; // quiet: one more fast success changes nothing
        }
        if (enabled) {
            record(token, slow);
        }
    }

    /**
     * Records a call that threw.
     *
     * @param token the value returned by {@link #acquire()}
     * @param start the value returned by {@link #startTimer()}
     * @param failure the exception thrown by the call
     */
    public void onFailure(long token, long start, Throwable failure) {
        if (!enabled) {
            return;
        }
        switch (classification.get(failure.getClass())) {
            case FAILURE -> record(token, CountWindow.FAILURE | slowBit(start));
            case SUCCESS -> record(token, slowBit(start));
            case IGNORED -> release(token);
        }
    }

    private int slowBit(long start) {
        return timed && ticker.getAsLong() - start >= slowCallNanos ? CountWindow.SLOW : 0;
    }

    private void record(long token, int outcome) {
        Phase current = phase();
        if (current.generation != token) {
            return; // the call started in an earlier phase
        }
        if (current instanceof Closed closed) {
            int changes = closed.window.record(outcome);
            if (outcome != 0) {
                // After the failure is counted. Write only on a change: quiet shares a cache line
                // with phase, which every call reads.
                if ((long) QUIET.getVolatile(this) != NOT_QUIET) {
                    QUIET.setVolatile(this, NOT_QUIET);
                }
            } else if ((changes & CountWindow.MAYBE_QUIET) != 0 && closed.window.onlyFastSuccesses()) {
                QUIET.setVolatile(this, token);
                if (!closed.window.onlyFastSuccesses()) { // a failure raced in: undo
                    QUIET.compareAndSet(this, token, NOT_QUIET);
                }
            }
            if ((changes & CountWindow.EVALUATE) != 0) {
                evaluateClosed(closed);
            }
        } else if (current instanceof HalfOpen halfOpen) {
            halfOpen.window.record(outcome);
            if (halfOpen.window.completed() == halfOpenCalls) {
                evaluateHalfOpen(halfOpen);
            }
        }
    }

    /** An ignored probe gives its permit back, so that another call can probe instead. */
    private void release(long token) {
        Phase current = phase();
        if (current.generation == token && current instanceof HalfOpen halfOpen) {
            halfOpen.permits.incrementAndGet();
        }
    }

    private void evaluateClosed(Closed closed) {
        CountWindow window = closed.window;
        int calls = window.calls();
        if (calls >= minimumCalls && exceedsThresholds(window, calls)) {
            PHASE.compareAndSet(this, closed, new Open(closed.generation + 1, ticker.getAsLong()));
        }
    }

    private void evaluateHalfOpen(HalfOpen halfOpen) {
        Phase next = exceedsThresholds(halfOpen.window, halfOpenCalls)
                ? new Open(halfOpen.generation + 1, ticker.getAsLong())
                : new Closed(halfOpen.generation + 1, new CountWindow(windowSize, false));
        PHASE.compareAndSet(this, halfOpen, next);
    }

    private boolean exceedsThresholds(CountWindow window, int calls) {
        return window.failures() * 100f >= failureRateThreshold * calls
                || (timed && window.slowCalls() * 100f >= slowCallRateThreshold * calls);
    }

    private Phase phase() {
        return (Phase) PHASE.getOpaque(this);
    }

    /** Recounts the closed window's slots; used by tests when no thread is recording. */
    int[] closedWindowCounts() {
        Phase current = phase();
        if (!(current instanceof Closed closed)) {
            throw new IllegalStateException("not closed: " + current.state());
        }
        CountWindow window = closed.window;
        int[] recount = window.recount();
        return new int[] {window.calls(), window.failures(), window.slowCalls(), recount[0], recount[1], recount[2]};
    }

    private enum Outcome { SUCCESS, FAILURE, IGNORED }

    private abstract static sealed class Phase permits Closed, Open, HalfOpen {
        static final int CLOSED = 0;
        static final int OPEN = 1;
        static final int HALF_OPEN = 2;

        final int kind;
        final long generation;

        Phase(int kind, long generation) {
            this.kind = kind;
            this.generation = generation;
        }

        abstract CircuitBreakerState state();
    }

    private static final class Closed extends Phase {
        final CountWindow window;

        Closed(long generation, CountWindow window) {
            super(CLOSED, generation);
            this.window = window;
        }

        @Override
        CircuitBreakerState state() {
            return CircuitBreakerState.CLOSED;
        }
    }

    private static final class Open extends Phase {
        final long openedAt;

        Open(long generation, long openedAt) {
            super(OPEN, generation);
            this.openedAt = openedAt;
        }

        @Override
        CircuitBreakerState state() {
            return CircuitBreakerState.OPEN;
        }
    }

    private static final class HalfOpen extends Phase {
        final AtomicInteger permits;
        final CountWindow window;

        HalfOpen(long generation, int probes) {
            super(HALF_OPEN, generation);
            this.permits = new AtomicInteger(probes);
            this.window = new CountWindow(probes, true);
        }

        @Override
        CircuitBreakerState state() {
            return CircuitBreakerState.HALF_OPEN;
        }
    }

    /** Caches, per exception class, how it is counted. {@code ignoreOn} wins over {@code recordOn}. */
    private static final class Classification extends ClassValue<Outcome> {
        private final List<Class<? extends Throwable>> recordOn;
        private final List<Class<? extends Throwable>> ignoreOn;

        Classification(List<Class<? extends Throwable>> recordOn, List<Class<? extends Throwable>> ignoreOn) {
            this.recordOn = recordOn;
            this.ignoreOn = ignoreOn;
        }

        @Override
        protected Outcome computeValue(Class<?> type) {
            for (Class<? extends Throwable> ignore : ignoreOn) {
                if (ignore.isAssignableFrom(type)) {
                    return Outcome.IGNORED;
                }
            }
            for (Class<? extends Throwable> record : recordOn) {
                if (record.isAssignableFrom(type)) {
                    return Outcome.FAILURE;
                }
            }
            return Outcome.SUCCESS;
        }
    }
}
