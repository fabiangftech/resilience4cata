package dev.resilience4cata.generated;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import org.jspecify.annotations.Nullable;

/**
 * One deadline in a {@link TimeoutTimer}. Subclasses say what happens when it expires ({@link #run()},
 * called on a new virtual thread, never on the timer thread) and call {@link #cancel()} when it is no
 * longer needed. An entry is scheduled at most once.
 *
 * <p>States: {@code NEW} (pushed to an inbox, not yet seen by the timer) → {@code SCHEDULED} (in its
 * lane, set by the timer thread) → {@code FIRED} (set by the timer thread); {@code NEW} or
 * {@code SCHEDULED} → {@code CANCELLED} (set by {@link #cancel()}). Every transition is a
 * compare-and-set on the same field, so exactly one of firing and cancelling wins.
 */
abstract class TimerEntry implements Runnable {

    static final int NEW = 0;
    static final int SCHEDULED = 1;
    static final int CANCELLED = 2;
    static final int FIRED = 3;

    static final VarHandle STATE;

    static {
        try {
            STATE = MethodHandles.lookup().findVarHandle(TimerEntry.class, "state", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @SuppressWarnings("unused") // accessed through STATE
    private volatile int state = NEW;

    /** Written before the entry is pushed (published by the push), final afterwards. */
    TimeoutTimer.@Nullable Lane lane;
    /**
     * Link in an inbox while {@code NEW}, then in a cancelled stack after {@code SCHEDULED → CANCELLED}.
     * The two uses never overlap: the timer reads and clears the inbox link before it moves the entry
     * to {@code SCHEDULED}, and only a canceller that saw {@code SCHEDULED} writes it again.
     */
    @Nullable TimerEntry stackNext;

    // Owned by the timer thread.
    long deadline;
    @Nullable TimerEntry prev;
    @Nullable TimerEntry next;
    boolean linked;

    /**
     * Cancels the entry. Returns {@code true} if it will never fire, {@code false} if it fired (or was
     * cancelled) already. Never blocks and takes no lock.
     */
    final boolean cancel() {
        // Common case: cancelled before the timer thread saw the entry. The timer then drops it when it
        // drains the inbox; nothing else to do. Only atomicity matters here (the timer's CAS on the
        // same field decides who wins), so release is enough and avoids the acquire half on ARM.
        int witness = (int) STATE.compareAndExchangeRelease(this, NEW, CANCELLED);
        if (witness == NEW) {
            return true;
        }
        // Acquire: the timer cleared stackNext before moving the entry to SCHEDULED (a volatile CAS);
        // our write of stackNext in cancelled(...) must come after that clear.
        if (witness == SCHEDULED
                && (int) STATE.compareAndExchangeAcquire(this, SCHEDULED, CANCELLED) == SCHEDULED) {
            TimeoutTimer.Lane owner = lane;
            if (owner != null) {
                owner.timer.cancelled(this); // so the timer unlinks it now, not when it would have expired
            }
            return true;
        }
        return false;
    }

    /** The current state; used by the timer and by tests. */
    final int state() {
        return (int) STATE.getVolatile(this);
    }

    /** Called by the timer thread only. */
    final boolean tryMove(int from, int to) {
        return STATE.compareAndSet(this, from, to);
    }
}
