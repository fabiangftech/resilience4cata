package dev.resilience4cata.generated;

import dev.resilience4cata.AdaptiveLimitConfig;
import dev.resilience4cata.AdaptiveLimitHandle;
import dev.resilience4cata.AdaptiveLimitSnapshot;
import dev.resilience4cata.LimitExceededException;
import dev.resilience4cata.event.AdaptiveLimitEvent;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;

/**
 * Adaptive limit for generated code: limits how many calls run at the same time, like a bulkhead, and
 * moves the limit by itself from the latency of the calls (Netflix concurrency-limits style).
 *
 * <pre>{@code
 * long start = limit.acquire();          // throws the preallocated LimitExceededException when full
 * T result;
 * try {
 *     result = delegate.call();
 * } catch (Throwable e) {
 *     limit.onFailure(start, e);
 *     throw e;
 * }
 * limit.onSuccess(start);
 * return result;
 * }</pre>
 *
 * <p><b>Taking a slot</b> is the bulkhead's fail-fast path: a compare-and-set loop on one padded counter
 * against the current limit, which a full limit only reads. The limit is a plain {@code int} read with
 * opaque access, written only at the end of a window.
 *
 * <p><b>Samples.</b> Each call reads the clock when it takes its slot and when it completes (one pair per
 * call). A completed call (a success, or a failure listed in {@code dropOn}) adds its latency to this
 * thread's stripe of counters: cumulative latency, calls and drops, plus the most calls in flight seen
 * since the last window ended, each stripe on its own 128-byte line, so that threads do not write each
 * other's lines. The writes are lossy (a plain read and write, no compare-and-set): two threads that share
 * a stripe and race can lose a sample, which only leaves the window with fewer samples. The counters are
 * cumulative and never reset, so a racing write can only lose an increment, never bring back a count that
 * was already used; each window works on the difference with the previous one.
 *
 * <p><b>Windows.</b> The thread that completes a call after the window's deadline tries to end it: it takes
 * the window lock (one compare-and-set, tried only when the lock looks free), sums the stripes, and if the
 * window has at least {@code minWindowSamples} calls, gives its average latency, its most calls in flight
 * and whether any was dropped to the algorithm ({@link LimitAlgorithm}), stores the new limit, sets the
 * next deadline and publishes {@link AdaptiveLimitEvent.LimitChanged} if the limit moved. Other threads do
 * nothing more. A window with too few calls is extended by one more window (the deadline moves to the end
 * of this completion plus {@code window}) and keeps its samples, which count when it ends: a limiter with
 * little traffic takes the lock once per window, not on every call. There is no background
 * thread and no lock on the call path; the algorithm's state is only touched under the window lock.
 */
public final class AdaptiveLimitPolicy implements AdaptiveLimitHandle {

    /** Longs per stripe: 128 bytes, one cache line on Apple silicon, two on most other CPUs. */
    private static final int STRIDE = 16;
    private static final int SUM = 0;
    private static final int COUNT = 1;
    private static final int DROPS = 2;
    private static final int MAX_IN_FLIGHT = 3;

    private static final VarHandle CELL = MethodHandles.arrayElementVarHandle(long[].class);

    private final String id;
    private final AdaptiveLimitConfig config;
    private final boolean enabled;
    private final LongSupplier ticker;
    private final long windowNanos;
    private final int minWindowSamples;
    private final ClassValue<Boolean> drops;
    private final LimitExceededException rejection;
    private final PaddedCounter inFlight = new PaddedCounter();
    private final Window window;
    /** Padding, then one stripe per hash of the thread id, then padding. */
    private final long[] cells;
    private final int mask;
    private final LimitAlgorithm algorithm;
    private final @Nullable EventSink<AdaptiveLimitEvent.Rejected> rejected;
    private final @Nullable EventSink<AdaptiveLimitEvent.LimitChanged> changed;

    // Touched only by the thread that holds the window lock.
    private long usedCount;
    private long usedSum;
    private long usedDrops;

    // Written under the window lock, read by snapshots only.
    private volatile long windows;
    private volatile long windowLatency;
    private volatile double longTermLatency;

    /**
     * Creates an adaptive limit.
     *
     * @param id the adaptive limit's id, used in the rejection message
     * @param config the configuration
     * @param environment the clock and the event listeners
     */
    public AdaptiveLimitPolicy(String id, AdaptiveLimitConfig config, PolicyEnvironment environment) {
        this(id, config, environment, RetryBudget.stripes(Runtime.getRuntime().availableProcessors()));
    }

    /** Package-private, for tests: a fixed number of stripes (a power of two). */
    AdaptiveLimitPolicy(String id, AdaptiveLimitConfig config, PolicyEnvironment environment, int stripes) {
        this.id = id;
        this.config = config;
        this.enabled = config.enabled();
        this.ticker = environment.ticker;
        this.windowNanos = config.window().toNanos();
        this.minWindowSamples = config.minWindowSamples();
        this.drops = new Classification(config.dropOn(), config.ignoreOn());
        this.rejection = new LimitExceededException(id);
        this.window = new Window(config.initialLimit(), enabled ? ticker.getAsLong() + windowNanos : 0);
        this.cells = new long[(stripes + 2) * STRIDE];
        this.mask = stripes - 1;
        this.algorithm = LimitAlgorithm.of(config);
        this.rejected = environment.events.sink(AdaptiveLimitEvent.Rejected.class, id);
        this.changed = environment.events.sink(AdaptiveLimitEvent.LimitChanged.class, id);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public AdaptiveLimitConfig config() {
        return config;
    }

    @Override
    public int limit() {
        return window.limit();
    }

    @Override
    public int inFlight() {
        return inFlight.get();
    }

    @Override
    public AdaptiveLimitSnapshot snapshot() {
        return new AdaptiveLimitSnapshot(id, config.algorithm(), limit(), Math.clamp(inFlight(), 0, config.maxLimit()),
                config.minLimit(), config.maxLimit(), windows, Duration.ofNanos(windowLatency),
                Duration.ofNanos((long) longTermLatency));
    }

    /** Returns the preallocated exception that {@link #acquire()} throws when the limit is full. */
    public LimitExceededException rejection() {
        return rejection;
    }

    /**
     * Takes a slot.
     *
     * @return the time the call starts, to pass to {@link #onSuccess}, {@link #onFailure} (or {@link #release})
     * @throws LimitExceededException if as many calls as the limit are already running: nothing was taken
     */
    public long acquire() {
        if (!enabled) {
            return 0;
        }
        if (!inFlight.tryIncrement(window.limit())) {
            throw rejected();
        }
        return ticker.getAsLong();
    }

    /** Publishes {@link AdaptiveLimitEvent.Rejected} if someone listens, and returns the rejection. */
    private LimitExceededException rejected() {
        EventSink<AdaptiveLimitEvent.Rejected> sink = rejected;
        if (sink != null) {
            sink.publish(new AdaptiveLimitEvent.Rejected(id, ticker.getAsLong(), window.limit()));
        }
        return rejection;
    }

    /**
     * The call returned: frees its slot and adds its latency to the current window.
     *
     * @param start what {@link #acquire()} returned
     */
    public void onSuccess(long start) {
        if (!enabled) {
            return;
        }
        long end = ticker.getAsLong();
        sample(end, end - start, inFlight.decrement(), false);
    }

    /**
     * The call failed: frees its slot. A failure listed in {@code dropOn} (and not in {@code ignoreOn}) is a
     * sample with a drop; any other failure is not a sample.
     *
     * @param start what {@link #acquire()} returned
     * @param failure the call's failure
     */
    public void onFailure(long start, Throwable failure) {
        if (!enabled) {
            return;
        }
        if (drops.get(failure.getClass())) {
            long end = ticker.getAsLong();
            sample(end, end - start, inFlight.decrement(), true);
        } else {
            inFlight.decrement();
        }
    }

    /**
     * The asynchronous form for a streaming call (a method that returns a {@code Flux}): as
     * {@link #executeAsync(AsyncCall)}, the slot is held until the stream ends, but the latency sample is
     * the time to the stream's first element (or its end, if it has none): a stream's length says nothing
     * about the dependency.
     *
     * @param call the next layer
     * @param elements the streaming call, told of this attempt's listener before {@code call} starts
     * @param <T> the result type
     * @return a future that completes with the call's outcome, or fails with {@link LimitExceededException}
     */
    public <T> CompletableFuture<T> executeStreamAsync(AsyncCall<T> call, Elements elements) {
        if (!enabled) {
            return executeAsync(call);
        }
        long start;
        try {
            start = acquire();
        } catch (LimitExceededException e) {
            return CompletableFuture.failedFuture(e);
        }
        FirstElement first = new FirstElement(ticker);
        elements.listen(first, false);
        CompletionStage<T> stage = AsyncSupport.call(call);
        if (AsyncSupport.succeeded(stage)) { // ended during its subscription
            long end = ticker.getAsLong();
            sample(end, first.time(end) - start, inFlight.decrement(), false);
            return AsyncSupport.copy(stage);
        }
        LinkedFuture<T> result = new LinkedFuture<>(stage);
        stage.whenComplete((value, failure) -> {
            if (failure == null) {
                long end = ticker.getAsLong(); // the window ends by the stream's end, its sample is the first element
                sample(end, first.time(end) - start, inFlight.decrement(), false);
                result.complete(value);
            } else if (result.cancelling()) {
                release();
            } else {
                Throwable cause = AsyncSupport.unwrap(failure);
                if (drops.get(cause.getClass())) {
                    long end = ticker.getAsLong();
                    sample(end, first.time(end) - start, inFlight.decrement(), true);
                } else {
                    inFlight.decrement();
                }
                result.fail(cause);
            }
        });
        return result;
    }

    /** Frees a slot without a sample: the call was cancelled by its caller. */
    public void release() {
        if (enabled) {
            inFlight.decrement();
        }
    }

    /**
     * Adds one sample to this thread's stripe, then ends the window if it is over.
     *
     * @param inFlightBefore the calls in flight just before this one freed its slot, this one included
     */
    private void sample(long end, long rtt, int inFlightBefore, boolean dropped) {
        long[] stripes = cells;
        int base = (1 + RetryBudget.stripe(Thread.currentThread().threadId(), mask)) * STRIDE;
        // Lossy on purpose (see the class): plain reads and writes, opaque so that they are not torn or hoisted.
        CELL.setOpaque(stripes, base + SUM, (long) CELL.getOpaque(stripes, base + SUM) + Math.max(rtt, 0));
        CELL.setOpaque(stripes, base + COUNT, (long) CELL.getOpaque(stripes, base + COUNT) + 1);
        if (dropped) {
            CELL.setOpaque(stripes, base + DROPS, (long) CELL.getOpaque(stripes, base + DROPS) + 1);
        }
        if (inFlightBefore > (long) CELL.getOpaque(stripes, base + MAX_IN_FLIGHT)) {
            CELL.setOpaque(stripes, base + MAX_IN_FLIGHT, (long) inFlightBefore);
        }
        if (end - window.deadline() >= 0) {
            endWindow(end);
        }
    }

    /** The window is over: ends it if this thread gets the window lock and the window has enough samples. */
    private void endWindow(long end) {
        Window current = window;
        if (!current.tryLock()) {
            return;
        }
        try {
            // Under the lock: the deadline another thread set when it ended the previous window is visible.
            if (end - current.deadline() < 0) {
                return;
            }
            closeWindow(current, end);
        } finally {
            current.unlock();
        }
    }

    /** Ends the window if it has enough samples. Runs under the window lock. */
    // java:S3078: windows has one writer at a time, the thread that holds the window lock; snapshots only read it
    @SuppressWarnings("java:S3078")
    private void closeWindow(Window current, long end) {
        long[] stripes = cells;
        long count = 0;
        long sum = 0;
        long dropCount = 0;
        for (int base = STRIDE; base < stripes.length - STRIDE; base += STRIDE) {
            count += (long) CELL.getOpaque(stripes, base + COUNT);
            sum += (long) CELL.getOpaque(stripes, base + SUM);
            dropCount += (long) CELL.getOpaque(stripes, base + DROPS);
        }
        long samples = count - usedCount;
        if (samples < minWindowSamples) {
            // Not enough calls yet: check again one window from now, so that a quiet limiter takes the lock and
            // scans the stripes once per window, not on every completion. The samples are kept (usedCount and
            // the stripes' max in flight are untouched): they count in the next window.
            current.setDeadline(end + windowNanos);
            return;
        }
        int maxInFlight = 0;
        for (int base = STRIDE; base < stripes.length - STRIDE; base += STRIDE) {
            maxInFlight = (int) Math.max(maxInFlight, (long) CELL.getOpaque(stripes, base + MAX_IN_FLIGHT));
            CELL.setOpaque(stripes, base + MAX_IN_FLIGHT, 0L);
        }
        long rtt = Math.max(1, (sum - usedSum) / samples);
        boolean dropped = dropCount != usedDrops;
        usedCount = count;
        usedSum = sum;
        usedDrops = dropCount;
        int from = current.limit();
        int to = algorithm.update(from, rtt, maxInFlight, dropped);
        current.setDeadline(end + windowNanos);
        windowLatency = rtt;
        longTermLatency = algorithm.longTermNanos();
        windows = windows + 1;
        if (to != from) {
            current.setLimit(to);
            limitChanged(end, from, to, rtt);
        }
    }

    private void limitChanged(long end, int from, int to, long rtt) {
        EventSink<AdaptiveLimitEvent.LimitChanged> sink = changed;
        if (sink != null) {
            sink.publish(new AdaptiveLimitEvent.LimitChanged(id, end, from, to, rtt));
        }
    }

    /** Test seam: the window lock, as {@link #endWindow} takes it. */
    boolean tryLockWindowForTest() {
        return window.tryLock();
    }

    /** Test seam: frees the window lock taken with {@link #tryLockWindowForTest()}. */
    void unlockWindowForTest() {
        window.unlock();
    }

    /** Test seam: ends the window at {@code end} as a completing call would, after the deadline check. */
    void endWindowForTest(long end) {
        endWindow(end);
    }

    /** Test seam: windows ended so far. */
    long windowsForTest() {
        return windows;
    }

    /** Caches, per exception class, whether it is a drop. {@code ignoreOn} wins over {@code dropOn}. */
    private static final class Classification extends ClassValue<Boolean> {
        private final List<Class<? extends Throwable>> dropOn;
        private final List<Class<? extends Throwable>> ignoreOn;

        Classification(List<Class<? extends Throwable>> dropOn, List<Class<? extends Throwable>> ignoreOn) {
            this.dropOn = dropOn;
            this.ignoreOn = ignoreOn;
        }

        @Override
        protected Boolean computeValue(Class<?> type) {
            for (Class<? extends Throwable> ignore : ignoreOn) {
                if (ignore.isAssignableFrom(type)) {
                    return Boolean.FALSE;
                }
            }
            for (Class<? extends Throwable> drop : dropOn) {
                if (drop.isAssignableFrom(type)) {
                    return Boolean.TRUE;
                }
            }
            return Boolean.FALSE;
        }
    }

    /**
     * Runs an asynchronous call in the adaptive limit: the slot is held until the call's stage completes,
     * and the latency is measured to that completion. A full limit is a failed future. Cancelling the
     * returned future cancels the stage, and frees the slot without a sample.
     *
     * @param call the next layer
     * @param <T> the result type
     * @return a future that completes with the call's outcome
     */
    public <T> CompletableFuture<T> executeAsync(AsyncCall<T> call) {
        long start;
        try {
            start = acquire();
        } catch (LimitExceededException e) {
            return CompletableFuture.failedFuture(e);
        }
        CompletionStage<T> stage = AsyncSupport.call(call);
        if (AsyncSupport.succeeded(stage)) {
            onSuccess(start);
            return AsyncSupport.copy(stage);
        }
        LinkedFuture<T> result = new LinkedFuture<>(stage);
        stage.whenComplete((value, failure) -> {
            if (failure == null) {
                onSuccess(start);
                result.complete(value);
            } else if (result.cancelling()) {
                release(); // cancelled by the caller: says nothing about the dependency
            } else {
                Throwable cause = AsyncSupport.unwrap(failure);
                onFailure(start, cause);
                result.fail(cause);
            }
        });
        return result;
    }

    /** Padding before a hot field, so that it does not share a cache line with what precedes it in memory. */
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

    /** The calls in flight. Superclass fields are laid out first, so the counter sits between two blocks of padding. */
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

        /** Frees one slot; returns the count before, this call included. */
        int decrement() {
            return (int) VALUE.getAndAddRelease(this, -1);
        }

        int get() {
            return (int) VALUE.getVolatile(this);
        }
    }

    // java:S1700: the window's fields, read on every call and written once per window
    @SuppressWarnings("java:S1700")
    private static class WindowFields extends PadBefore {
        static final VarHandle LIMIT;
        static final VarHandle DEADLINE;
        static final VarHandle LOCK;

        static {
            try {
                MethodHandles.Lookup lookup = MethodHandles.lookup();
                LIMIT = lookup.findVarHandle(WindowFields.class, "limit", int.class);
                DEADLINE = lookup.findVarHandle(WindowFields.class, "deadline", long.class);
                LOCK = lookup.findVarHandle(WindowFields.class, "lock", int.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        @SuppressWarnings({"unused", "java:S1845"}) // accessed through LIMIT
        int limit;
        @SuppressWarnings({"unused", "java:S1845"}) // accessed through DEADLINE
        long deadline;
        @SuppressWarnings({"unused", "java:S1845"}) // accessed through LOCK
        int lock;
    }

    /**
     * The current limit, the end of the current window, and the window lock: read by every call (the limit
     * when it takes a slot, the deadline when it completes), written once per window. Padded on both sides,
     * away from the counter of calls in flight that every call writes. The limit and the deadline guard no
     * other memory, so they are read and written with opaque access; the lock orders the windows' work.
     */
    @SuppressWarnings("unused")
    private static final class Window extends WindowFields {
        long q0;
        long q1;
        long q2;
        long q3;
        long q4;
        long q5;
        long q6;
        long q7;

        Window(int limit, long deadline) {
            LIMIT.setOpaque(this, limit);
            DEADLINE.setOpaque(this, deadline);
        }

        int limit() {
            return (int) LIMIT.getOpaque(this);
        }

        void setLimit(int limit) {
            LIMIT.setOpaque(this, limit);
        }

        long deadline() {
            return (long) DEADLINE.getOpaque(this);
        }

        void setDeadline(long deadline) {
            DEADLINE.setOpaque(this, deadline);
        }

        /** Takes the window lock; only reads it when another thread holds it. */
        boolean tryLock() {
            return (int) LOCK.getOpaque(this) == 0 && LOCK.compareAndSet(this, 0, 1);
        }

        void unlock() {
            LOCK.setRelease(this, 0);
        }
    }
}
