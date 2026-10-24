package dev.resilience4cata.generated;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lock-free, allocation-free sliding window over the last {@code size} calls.
 *
 * <p>Each call takes the next slot with {@code getAndAdd} on the cursor and swaps its outcome in with
 * {@code getAndSet}. Because {@code getAndSet} returns the exact outcome it replaced, every counter
 * change is applied exactly once: the counters can lag for an instant under concurrent writes, but
 * they never drift.
 *
 * <p>An outcome equal to the one already in its slot does not rewrite the slot: under contention,
 * writes to shared cache lines cost more than the lack of locks saves. (Skipping successes on a
 * window that holds only fast successes is done one level up, by {@link CircuitBreakerPolicy}.)
 *
 * <p>The counters are plain fields updated through {@link VarHandle}s. The cursor, written by every
 * call, is a separate object so that it does not share a cache line with them; and {@code completed}
 * is only maintained when the caller needs it (half-open probes), since it would be one more write
 * per call.
 */
final class CountWindow implements SlidingWindow {

    private static final int FILLED = 1;

    private static final VarHandle CALLS;
    private static final VarHandle FAILURES;
    private static final VarHandle SLOW_CALLS;
    private static final VarHandle COMPLETED;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            CALLS = lookup.findVarHandle(CountWindow.class, "calls", int.class);
            FAILURES = lookup.findVarHandle(CountWindow.class, "failures", int.class);
            SLOW_CALLS = lookup.findVarHandle(CountWindow.class, "slowCalls", int.class);
            COMPLETED = lookup.findVarHandle(CountWindow.class, "completed", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final int size;
    private final boolean countCompleted;
    private final AtomicIntegerArray slots;
    private final AtomicLong cursor = new AtomicLong();

    @SuppressWarnings("unused") // accessed through the VarHandles above
    private int calls;
    @SuppressWarnings("unused")
    private int failures;
    @SuppressWarnings("unused")
    private int slowCalls;
    @SuppressWarnings("unused")
    private int completed;

    /**
     * @param size number of calls in the window
     * @param countCompleted whether to maintain {@link #completed()}
     */
    CountWindow(int size, boolean countCompleted) {
        this.size = size;
        this.countCompleted = countCompleted;
        this.slots = new AtomicIntegerArray(size);
    }

    @Override
    public int record(int outcome, long now) {
        int index = (int) (cursor.getAndIncrement() % size);
        int value = outcome | FILLED;
        if (slots.getOpaque(index) == value) {
            if (countCompleted) {
                COMPLETED.getAndAdd(this, 1);
            }
            return 0; // same outcome as the one it replaces: nothing changes
        }
        int previous = slots.getAndSet(index, value);
        int failureDelta = (outcome & FAILURE) - (previous & FAILURE);
        if (failureDelta != 0) {
            FAILURES.getAndAdd(this, failureDelta / FAILURE);
        }
        int slowDelta = (outcome & SLOW) - (previous & SLOW);
        if (slowDelta != 0) {
            SLOW_CALLS.getAndAdd(this, slowDelta / SLOW);
        }
        boolean newSlot = (previous & FILLED) == 0;
        if (newSlot) {
            CALLS.getAndAdd(this, 1);
        }
        if (countCompleted) {
            COMPLETED.getAndAdd(this, 1);
        }
        if (outcome != 0) {
            return EVALUATE;
        }
        boolean changed = newSlot || previous != FILLED;
        return (newSlot ? EVALUATE : 0) | (changed ? MAYBE_QUIET : 0);
    }

    @Override
    public boolean exceeds(int minimumCalls, float failureRateThreshold, float slowCallRateThreshold,
            boolean timed, long now) {
        int recorded = calls();
        return recorded >= minimumCalls
                && (failures() * 100f >= failureRateThreshold * recorded
                        || (timed && slowCalls() * 100f >= slowCallRateThreshold * recorded));
    }

    @Override
    public boolean onlyFastSuccesses() {
        return (int) FAILURES.getVolatile(this) == 0 && (int) SLOW_CALLS.getVolatile(this) == 0
                && (int) CALLS.getVolatile(this) == size;
    }

    /** Number of slots that hold an outcome, at most {@code size}. */
    int calls() {
        return (int) CALLS.getVolatile(this);
    }

    int failures() {
        return (int) FAILURES.getVolatile(this);
    }

    int slowCalls() {
        return (int) SLOW_CALLS.getVolatile(this);
    }

    /** Number of {@link #record} calls that have finished updating the counters; only if {@code countCompleted}. */
    int completed() {
        return (int) COMPLETED.getVolatile(this);
    }

    /** Recounts the slots. Only meaningful when no thread is recording; used by tests. */
    int[] recount() {
        int filled = 0;
        int failed = 0;
        int slow = 0;
        for (int i = 0; i < size; i++) {
            int slot = slots.get(i);
            filled += slot & FILLED;
            failed += (slot & FAILURE) / FAILURE;
            slow += (slot & SLOW) / SLOW;
        }
        return new int[] {filled, failed, slow};
    }
}
