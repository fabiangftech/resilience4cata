package dev.resilience4cata.generated;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lock-free, allocation-free sliding window over the last {@code size} calls.
 *
 * <p>Each call takes the next slot with {@code getAndIncrement} and swaps its outcome in with
 * {@code getAndSet}. Because {@code getAndSet} returns the exact outcome it replaced, every counter
 * change is applied exactly once: the counters can lag for an instant under concurrent writes, but
 * they never drift.
 *
 * <p>Two shortcuts keep threads from writing to shared memory when nothing changes, which matters
 * more than locking once several cores share one window:
 * <ul>
 *   <li>A fast success on a full window with no failures and no slow calls changes nothing that can
 *       be observed, so it is not recorded at all (when {@code skipNoOpSuccesses} is set).</li>
 *   <li>An outcome equal to the one already in its slot does not rewrite the slot.</li>
 * </ul>
 */
final class CountWindow {

    static final int FILLED = 1;
    static final int FAILURE = 2;
    static final int SLOW = 4;

    private final int size;
    private final boolean skipNoOpSuccesses;
    private final AtomicIntegerArray slots;
    private final AtomicLong cursor = new AtomicLong();
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger failures = new AtomicInteger();
    private final AtomicInteger slowCalls = new AtomicInteger();
    private final AtomicInteger completed = new AtomicInteger();

    /**
     * @param size number of calls in the window
     * @param skipNoOpSuccesses whether to drop successes that cannot change the window; must be
     *     {@code false} when the caller relies on {@link #completed()} counting every call
     */
    CountWindow(int size, boolean skipNoOpSuccesses) {
        this.size = size;
        this.skipNoOpSuccesses = skipNoOpSuccesses;
        this.slots = new AtomicIntegerArray(size);
    }

    /**
     * Records one outcome.
     *
     * @param outcome {@link #FAILURE} and/or {@link #SLOW} bits, or 0 for a fast success
     * @return {@code true} if the rates may have risen and must be evaluated: the outcome is a
     *     failure or slow, or the window is still filling up
     */
    boolean record(int outcome) {
        if (skipNoOpSuccesses && outcome == 0 && failures.get() == 0 && slowCalls.get() == 0 && calls.get() == size) {
            return false; // every slot already holds a fast success
        }
        int index = (int) (cursor.getAndIncrement() % size);
        int value = outcome | FILLED;
        if (slots.get(index) == value) {
            completed.incrementAndGet();
            return false; // same outcome as the one it replaces: nothing changes
        }
        int previous = slots.getAndSet(index, value);
        int failureDelta = (outcome & FAILURE) - (previous & FAILURE);
        if (failureDelta != 0) {
            failures.addAndGet(failureDelta / FAILURE);
        }
        int slowDelta = (outcome & SLOW) - (previous & SLOW);
        if (slowDelta != 0) {
            slowCalls.addAndGet(slowDelta / SLOW);
        }
        boolean newSlot = (previous & FILLED) == 0;
        if (newSlot) {
            calls.incrementAndGet();
        }
        completed.incrementAndGet();
        return newSlot || (outcome & (FAILURE | SLOW)) != 0;
    }

    /** Number of slots that hold an outcome, at most {@code size}. */
    int calls() {
        return calls.get();
    }

    int failures() {
        return failures.get();
    }

    int slowCalls() {
        return slowCalls.get();
    }

    /** Number of {@link #record} calls that have finished updating the counters. */
    int completed() {
        return completed.get();
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
