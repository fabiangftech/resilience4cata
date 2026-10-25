package dev.resilience4cata.generated;

import dev.resilience4cata.CallNotPermittedException;
import dev.resilience4cata.CircuitBreakerConfig;
import dev.resilience4cata.CircuitBreakerHandle;
import dev.resilience4cata.CircuitBreakerSnapshot;
import dev.resilience4cata.CircuitBreakerState;
import dev.resilience4cata.event.CircuitBreakerEvent;
import dev.resilience4cata.Classifier;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;

/**
 * Circuit breaker for generated code: lock-free, and allocation-free except on state transitions
 * (and, for time-based windows, one bucket per second).
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
 * <p><b>Quiet state.</b> While the breaker is closed and its count window holds only fast
 * successes, another fast success changes nothing. The {@code quiet} field then holds the closed
 * phase's generation, and {@link #acquire()} and {@link #onSuccess} only read that one field: no
 * phase or window access, no writes. Ordering keeps it correct:
 * <ul>
 *   <li>A failure is counted in the window <em>before</em> {@code quiet} is cleared, and every
 *       transition swaps the phase <em>before</em> clearing {@code quiet}.</li>
 *   <li>A success sets {@code quiet} <em>before</em> re-checking both the window and the phase, and
 *       undoes it if either changed.</li>
 * </ul>
 * So one of the two sides always sees the other. Other reads of {@code phase} and {@code quiet} are
 * opaque: every field they lead to is final, so the objects are safely published without acquire
 * barriers, which are not free on ARM.
 */
public final class CircuitBreakerPolicy implements CircuitBreakerHandle {

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

    private final String id;
    private final CircuitBreakerConfig config;
    private final boolean timeWindow;
    private final int windowSize;
    private final int minimumCalls;
    private final float failureRateThreshold;
    private final float slowCallRateThreshold;
    private final boolean timed;
    private final long slowCallNanos;
    private final long waitInOpenNanos;
    private final long maxWaitInHalfOpenNanos;
    private final int halfOpenCalls;
    private final ClassValue<Outcome> classification;
    private final CallNotPermittedException rejection;
    private final LongSupplier ticker;
    private final @Nullable EventSink<CircuitBreakerEvent.StateTransition> transitions;
    private final @Nullable EventSink<CircuitBreakerEvent.CallNotPermitted> notPermitted;

    @SuppressWarnings("unused") // accessed through PHASE
    private volatile Phase phase;
    @SuppressWarnings("unused") // accessed through QUIET
    private volatile long quiet = NOT_QUIET;

    /**
     * Creates a circuit breaker in its initial state: closed, or disabled if the configuration says so.
     *
     * @param id the breaker's id, used in the rejection message
     * @param config the configuration
     */
    public CircuitBreakerPolicy(String id, CircuitBreakerConfig config) {
        this(id, config, PolicyEnvironment.DEFAULT);
    }

    CircuitBreakerPolicy(String id, CircuitBreakerConfig config, LongSupplier ticker) {
        this(id, config, PolicyEnvironment.forTest(ticker, RetryPolicy.Sleeper.NONE));
    }

    /**
     * Creates a circuit breaker in its initial state: closed, or disabled if the configuration says so.
     *
     * @param id the breaker's id, used in the rejection message
     * @param config the configuration
     * @param environment the clock to use
     */
    public CircuitBreakerPolicy(String id, CircuitBreakerConfig config, PolicyEnvironment environment) {
        this.id = id;
        this.config = config;
        this.timeWindow = config.windowType() == CircuitBreakerConfig.WindowType.TIME;
        this.windowSize = config.windowSize();
        this.minimumCalls = timeWindow ? config.minimumCalls() : Math.min(config.minimumCalls(), config.windowSize());
        this.failureRateThreshold = config.failureRateThreshold();
        this.slowCallRateThreshold = config.slowCallRateThreshold();
        this.slowCallNanos = config.slowCallDuration().toNanos();
        this.timed = slowCallNanos > 0;
        this.waitInOpenNanos = config.waitInOpen().toNanos();
        this.maxWaitInHalfOpenNanos = config.maxWaitInHalfOpen().toNanos();
        this.halfOpenCalls = config.halfOpenCalls();
        this.classification = new Classification(config.recordOn(), config.ignoreOn());
        this.rejection = new CallNotPermittedException(id);
        this.ticker = environment.ticker;
        this.transitions = environment.events.sink(CircuitBreakerEvent.StateTransition.class, id);
        this.notPermitted = environment.events.sink(CircuitBreakerEvent.CallNotPermitted.class, id);
        this.phase = initialPhase(0);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public CircuitBreakerConfig config() {
        return config;
    }

    @Override
    public CircuitBreakerState state() {
        return phase().state();
    }

    @Override
    public CircuitBreakerSnapshot snapshot() {
        Phase current = phase();
        int[] counts;
        int minimum;
        if (current instanceof Closed closed) {
            counts = closed.window.counts(timeWindow ? ticker.getAsLong() : 0L);
            minimum = minimumCalls;
        } else if (current instanceof HalfOpen halfOpen) {
            counts = halfOpen.window.counts(0L);
            minimum = halfOpenCalls;
        } else {
            counts = new int[3];
            minimum = 1;
        }
        int calls = counts[0];
        boolean evaluated = calls > 0 && calls >= minimum;
        float failureRate = evaluated ? counts[1] * 100f / calls : -1f;
        float slowCallRate = evaluated && timed ? counts[2] * 100f / calls : -1f;
        return new CircuitBreakerSnapshot(id, current.state(), failureRate, slowCallRate, calls, counts[1], counts[2]);
    }

    /** Returns the preallocated exception to throw when {@link #acquire()} returns {@link #REJECTED}. */
    public CallNotPermittedException rejection() {
        return rejection;
    }

    @Override
    public void forceOpen() {
        transitionFromAny(generation -> new ForcedOpen(generation));
    }

    @Override
    public void disable() {
        transitionFromAny(generation -> new Disabled(generation));
    }

    @Override
    public void reset() {
        transitionFromAny(this::initialPhase);
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
        if (current.kind == Phase.CLOSED || current.kind == Phase.DISABLED) {
            return current.generation;
        }
        return acquireSlow(current);
    }

    private long acquireSlow(Phase current) {
        while (true) {
            if (current instanceof Open open) {
                long now = ticker.getAsLong();
                if (now - open.openedAt < waitInOpenNanos) {
                    return rejected(CircuitBreakerState.OPEN, now);
                }
                Phase halfOpen = new HalfOpen(open.generation + 1, halfOpenCalls, now);
                if (!transition(current, halfOpen)) {
                    current = phase();
                    continue;
                }
                current = halfOpen;
            }
            if (current instanceof HalfOpen halfOpen) {
                if (maxWaitInHalfOpenNanos > 0) {
                    long now = ticker.getAsLong();
                    if (now - halfOpen.startedAt >= maxWaitInHalfOpenNanos) {
                        // The probes took too long: open again.
                        transition(current, new Open(halfOpen.generation + 1, now));
                        return rejected(CircuitBreakerState.OPEN, now);
                    }
                }
                // Never below zero: a rejected call must not use up a permit that an ignored probe gives back.
                AtomicInteger permits = halfOpen.permits;
                int left;
                do {
                    left = permits.get();
                    if (left <= 0) {
                        return rejected(CircuitBreakerState.HALF_OPEN, 0L);
                    }
                } while (!permits.compareAndSet(left, left - 1));
                return halfOpen.generation;
            }
            if (current.kind == Phase.FORCED_OPEN) {
                return rejected(CircuitBreakerState.FORCED_OPEN, 0L);
            }
            return current.generation;
        }
    }

    /**
     * Publishes {@link CircuitBreakerEvent.CallNotPermitted} if someone listens, and returns {@link #REJECTED}.
     * Without listeners this is one null check: no allocation and no clock read.
     *
     * @param now the clock reading the caller already has, or 0 to read it here (only when publishing)
     */
    private long rejected(CircuitBreakerState state, long now) {
        EventSink<CircuitBreakerEvent.CallNotPermitted> sink = notPermitted;
        if (sink != null) {
            sink.publish(new CircuitBreakerEvent.CallNotPermitted(id, now != 0L ? now : ticker.getAsLong(), state));
        }
        return REJECTED;
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
        record(token, slow);
    }

    /**
     * Records a call that threw.
     *
     * @param token the value returned by {@link #acquire()}
     * @param start the value returned by {@link #startTimer()}
     * @param failure the exception thrown by the call
     */
    public void onFailure(long token, long start, Throwable failure) {
        switch (classification.get(failure.getClass())) {
            case FAILURE -> record(token, SlidingWindow.FAILURE | slowBit(start));
            case SUCCESS -> record(token, slowBit(start));
            case IGNORED -> release(token);
        }
    }

    /**
     * Records a call that threw, for a method with a {@link Classifier}: the classifier's verdict
     * replaces {@code recordOn}, while {@code ignoreOn} still wins over it.
     *
     * @param token the value returned by {@link #acquire()}
     * @param start the value returned by {@link #startTimer()}
     * @param failure the exception thrown by the call
     * @param outcome what the method's classifier returned for {@code failure}
     */
    public void onFailure(long token, long start, Throwable failure, Classifier.Outcome outcome) {
        onResult(token, start, classification.get(failure.getClass()) == Outcome.IGNORED
                ? Classifier.Outcome.IGNORE : outcome);
    }

    /**
     * Records a call that returned normally, for a method with a {@link Classifier}: the result is
     * recorded as the classifier said. {@code IGNORE} records nothing and gives a half-open probe
     * permit back; a {@code SUCCESS} costs the same as {@link #onSuccess}.
     *
     * @param token the value returned by {@link #acquire()}
     * @param start the value returned by {@link #startTimer()}
     * @param outcome what the method's classifier returned for the result
     */
    public void onResult(long token, long start, Classifier.Outcome outcome) {
        switch (outcome) {
            case SUCCESS -> onSuccess(token, start);
            case FAILURE -> record(token, SlidingWindow.FAILURE | slowBit(start));
            case IGNORE -> release(token);
        }
    }

    private int slowBit(long start) {
        return timed && ticker.getAsLong() - start >= slowCallNanos ? SlidingWindow.SLOW : 0;
    }

    private void record(long token, int outcome) {
        Phase current = phase();
        if (current.generation != token) {
            return; // the call started in an earlier phase
        }
        if (current instanceof Closed closed) {
            long now = timeWindow ? ticker.getAsLong() : 0L;
            int changes = closed.window.record(outcome, now);
            if (outcome != 0) {
                clearQuiet(); // after the failure is counted
            } else if ((changes & SlidingWindow.MAYBE_QUIET) != 0 && closed.window.onlyFastSuccesses()) {
                publishQuiet(closed, token);
            }
            if ((changes & SlidingWindow.EVALUATE) != 0
                    && closed.window.exceeds(minimumCalls, failureRateThreshold, slowCallRateThreshold, timed, now)) {
                transition(closed, new Open(closed.generation + 1, timeWindow ? now : ticker.getAsLong()));
            }
        } else if (current instanceof HalfOpen halfOpen) {
            halfOpen.window.record(outcome, 0L);
            if (halfOpen.window.completed() == halfOpenCalls) {
                evaluateHalfOpen(halfOpen);
            }
        }
        // Open, forced open, disabled: nothing is recorded.
    }

    /**
     * Sets the quiet state, then re-checks the window and the phase: if a failure was counted or the
     * phase changed meanwhile, the other side may already have cleared {@code quiet}, so undo.
     */
    private void publishQuiet(Closed closed, long token) {
        QUIET.setVolatile(this, token);
        if (!closed.window.onlyFastSuccesses() || PHASE.getVolatile(this) != closed) {
            QUIET.compareAndSet(this, token, NOT_QUIET);
        }
    }

    /** Test seam: the current phase object, to replay a late {@link #publishQuiet} after a transition. */
    Object phaseForTest() {
        return phase();
    }

    /** Test seam: runs {@link #publishQuiet} as a success that read {@code closedPhase} earlier would. */
    void publishQuietForTest(Object closedPhase, long token) {
        publishQuiet((Closed) closedPhase, token);
    }

    /** An ignored probe gives its permit back, so that another call can probe instead. */
    private void release(long token) {
        Phase current = phase();
        if (current.generation == token && current instanceof HalfOpen halfOpen) {
            halfOpen.permits.incrementAndGet();
        }
    }

    private void evaluateHalfOpen(HalfOpen halfOpen) {
        Phase next = halfOpen.window.exceeds(halfOpenCalls, failureRateThreshold, slowCallRateThreshold, timed, 0L)
                ? new Open(halfOpen.generation + 1, ticker.getAsLong())
                : new Closed(halfOpen.generation + 1, newWindow());
        transition(halfOpen, next);
    }

    private Phase initialPhase(long generation) {
        return config.enabled() ? new Closed(generation, newWindow()) : new Disabled(generation);
    }

    private SlidingWindow newWindow() {
        return timeWindow ? new TimeWindow(windowSize) : new CountWindow(windowSize, false);
    }

    /**
     * Swaps the phase, then clears the quiet state, which only a closed phase can have. The thread whose
     * compare-and-set wins publishes the transition, after the new phase is in place: exactly once.
     */
    private boolean transition(Phase from, Phase to) {
        if (PHASE.compareAndSet(this, from, to)) {
            clearQuiet();
            EventSink<CircuitBreakerEvent.StateTransition> sink = transitions;
            if (sink != null) {
                sink.publish(new CircuitBreakerEvent.StateTransition(id, ticker.getAsLong(), from.state(), to.state()));
            }
            return true;
        }
        return false;
    }

    private void transitionFromAny(java.util.function.LongFunction<Phase> next) {
        while (true) {
            Phase current = phase();
            if (transition(current, next.apply(current.generation + 1))) {
                return;
            }
        }
    }

    /** Writes only on a change: {@code quiet} shares a cache line with {@code phase}, which every call reads. */
    private void clearQuiet() {
        if ((long) QUIET.getVolatile(this) != NOT_QUIET) {
            QUIET.setVolatile(this, NOT_QUIET);
        }
    }

    private Phase phase() {
        return (Phase) PHASE.getOpaque(this);
    }

    /** Recounts the closed count window's slots; used by tests when no thread is recording. */
    int[] closedWindowCounts() {
        Phase current = phase();
        if (!(current instanceof Closed closed) || !(closed.window instanceof CountWindow window)) {
            throw new IllegalStateException("not closed with a count window: " + current.state());
        }
        int[] recount = window.recount();
        return new int[] {window.calls(), window.failures(), window.slowCalls(), recount[0], recount[1], recount[2]};
    }

    private enum Outcome { SUCCESS, FAILURE, IGNORED }

    private abstract static sealed class Phase permits Closed, Open, HalfOpen, ForcedOpen, Disabled {
        static final int CLOSED = 0;
        static final int OPEN = 1;
        static final int HALF_OPEN = 2;
        static final int FORCED_OPEN = 3;
        static final int DISABLED = 4;

        final int kind;
        final long generation;

        Phase(int kind, long generation) {
            this.kind = kind;
            this.generation = generation;
        }

        abstract CircuitBreakerState state();
    }

    private static final class Closed extends Phase {
        final SlidingWindow window;

        Closed(long generation, SlidingWindow window) {
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
        final long startedAt;

        HalfOpen(long generation, int probes, long startedAt) {
            super(HALF_OPEN, generation);
            this.permits = new AtomicInteger(probes);
            this.window = new CountWindow(probes, true);
            this.startedAt = startedAt;
        }

        @Override
        CircuitBreakerState state() {
            return CircuitBreakerState.HALF_OPEN;
        }
    }

    private static final class ForcedOpen extends Phase {
        ForcedOpen(long generation) {
            super(FORCED_OPEN, generation);
        }

        @Override
        CircuitBreakerState state() {
            return CircuitBreakerState.FORCED_OPEN;
        }
    }

    private static final class Disabled extends Phase {
        Disabled(long generation) {
            super(DISABLED, generation);
        }

        @Override
        CircuitBreakerState state() {
            return CircuitBreakerState.DISABLED;
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

    /**
     * Runs an asynchronous call through the breaker: the call is recorded when its stage completes, and
     * a rejection is a failed future. Used by generated code for methods that return a stage.
     *
     * @param call the next layer
     * @param <T> the result type
     * @return a future that completes with the call's outcome
     */
    public <T> CompletableFuture<T> executeAsync(AsyncCall<T> call) {
        long token = acquire();
        if (token < 0) {
            return CompletableFuture.failedFuture(rejection());
        }
        long started = startTimer();
        CompletableFuture<T> result = new CompletableFuture<>();
        AsyncSupport.call(call).whenComplete((value, failure) -> {
            if (failure == null) {
                onSuccess(token, started);
                result.complete(value);
            } else {
                Throwable cause = AsyncSupport.unwrap(failure);
                onFailure(token, started, cause);
                result.completeExceptionally(cause);
            }
        });
        return result;
    }

    /**
     * Same as {@link #executeAsync(AsyncCall)}, with a classifier that decides how results and exceptions
     * are recorded (see {@link #onResult} and {@link #onFailure(long, long, Throwable, Classifier.Outcome)}).
     * An exception thrown by the classifier is recorded as a failure and fails the future.
     *
     * @param call the next layer
     * @param classifier the method's classifier
     * @param <T> the result type
     * @return a future that completes with the call's outcome
     */
    public <T> CompletableFuture<T> executeAsync(AsyncCall<T> call, Classifier<? super T> classifier) {
        return executeAsync(call, classifier, false);
    }

    /**
     * Same as {@link #executeAsync(AsyncCall, Classifier)}; with {@code insideRetry}, an exception thrown by
     * the classifier fails the future wrapped in a {@link ClassifierFailure}, so that the retry around this
     * breaker ends the call with it instead of classifying it as the call's failure.
     *
     * @param call the next layer
     * @param classifier the method's classifier
     * @param insideRetry {@code true} if a retry runs this breaker (it unwraps the {@code ClassifierFailure})
     * @param <T> the result type
     * @return a future that completes with the call's outcome
     */
    public <T> CompletableFuture<T> executeAsync(AsyncCall<T> call, Classifier<? super T> classifier,
            boolean insideRetry) {
        long token = acquire();
        if (token < 0) {
            return CompletableFuture.failedFuture(rejection());
        }
        long started = startTimer();
        CompletableFuture<T> result = new CompletableFuture<>();
        AsyncSupport.call(call).whenComplete((value, failure) -> {
            Throwable cause = failure == null ? null : AsyncSupport.unwrap(failure);
            Classifier.Outcome outcome;
            try {
                outcome = classifier.classify(cause == null ? value : null, cause);
            } catch (Throwable classifierFailure) {
                onResult(token, started, Classifier.Outcome.FAILURE);
                result.completeExceptionally(insideRetry ? new ClassifierFailure(classifierFailure) : classifierFailure);
                return;
            }
            if (cause == null) {
                onResult(token, started, outcome);
                result.complete(value);
            } else {
                onFailure(token, started, cause, outcome);
                result.completeExceptionally(cause);
            }
        });
        return result;
    }
}
