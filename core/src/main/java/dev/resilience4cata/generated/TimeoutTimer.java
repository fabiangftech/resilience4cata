package dev.resilience4cata.generated;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * Timer for asynchronous timeouts: scheduling and cancelling take no lock, read no clock and, in the
 * common case, write no memory shared with other callers.
 *
 * <p><b>Scheduling</b> pushes the entry onto one of several inboxes (lock-free stacks, one CAS),
 * chosen by thread id so that threads rarely push to the same one. Each inbox head sits in its own
 * padded object. The caller reads no clock.
 *
 * <p><b>The timer thread</b> wakes every tick. It reads the clock once, then:
 * <ol>
 *   <li>unlinks entries that were cancelled after it had scheduled them (they were pushed to a
 *       cancelled stack by {@link TimerEntry#cancel()}), so memory does not grow with long timeouts;
 *   <li>drains the inboxes: an entry already cancelled is dropped, any other one gets the deadline
 *       {@code now + duration} and is appended to its {@link Lane};
 *   <li>fires, on a new virtual thread, every entry whose deadline has passed.
 * </ol>
 * A lane holds the entries of one duration (one per timeout policy), so its entries are appended in
 * deadline order and expiring them only looks at the head: no heap, no wheel rounds.
 *
 * <p><b>Never early.</b> The deadline starts from the clock read <em>after</em> the timer took the
 * entry from its inbox, which is after the call started. <b>At most about two ticks late</b> (plus
 * whatever delay the OS adds to the timer thread's wake-ups): up to one tick before the entry is drained,
 * and up to one tick between its deadline and the tick that sees it. The tick is {@code duration / 16}
 * of the shortest duration in use, between 1 ms and 50 ms, as for the sync {@link Watchdog}.
 *
 * <p><b>Precise lanes</b> ({@link #preciseLane}, for hedge delays) trade one clock read per schedule for
 * precision: the caller passes the time it started, the deadline is {@code start + duration}, and the timer
 * thread wakes up at the earliest deadline of a precise lane instead of only at the next tick (at most 16
 * wake-ups per tick). A precise entry is drained within one tick, which is no longer than its duration
 * (unless the duration is below 1 ms), so it fires at its deadline plus the OS's wake-up delay. Entries are
 * appended in drain order, inbox by inbox (a precise lane's oldest first), and expiring still looks only at a lane's head, so
 * an entry drained after one that started later (another thread's inbox) waits for it: late by at most the
 * spread of the starts drained in one tick, at most one tick.
 *
 * <p><b>Cancelling</b> is one CAS on the entry's own state. If the timer has not drained the entry yet
 * (the call completed within about a tick, the common case), that is all; otherwise the entry is also
 * pushed to a cancelled stack and unlinked at the next tick. A drained entry is reachable from the timer
 * for at most one tick after it is cancelled; an undrained one for at most one tick after it was pushed.
 */
final class TimeoutTimer {

    private static final long MIN_TICK = 1_000_000L;
    private static final long MAX_TICK = 50_000_000L;

    private final Stripe[] stripes;
    private final int mask;
    private final AtomicLong tick = new AtomicLong(MAX_TICK);
    private final Consumer<Runnable> launcher;
    private final @Nullable AtomicBoolean started;
    @SuppressWarnings("java:S3077") // the reference is swapped whole; Thread itself is thread-safe
    private volatile @Nullable Thread thread;

    // Owned by the timer thread (or by the test calling tick).
    private final ArrayList<Lane> active = new ArrayList<>();
    private int linked;
    private boolean preciseDue; // a precise lane has entries; the earliest head's deadline is preciseDeadline
    private long preciseDeadline;
    private volatile int linkedSnapshot;

    /** The shared timer; its thread starts on the first {@link #schedule}. */
    static TimeoutTimer shared() {
        return Shared.INSTANCE;
    }

    private static final class Shared {
        // An entry fires with the context class loader of the call that scheduled it (Threads).
        static final TimeoutTimer INSTANCE = new TimeoutTimer(task -> Threads.startVirtual("resilience4cata-async",
                task instanceof TimerEntry entry ? entry.contextLoader : null, task), true);
    }

    /** Creates a timer that only moves when {@link #tick(long)} is called; fired tasks go to {@code launcher}. */
    TimeoutTimer(Consumer<Runnable> launcher) {
        this(launcher, false);
    }

    private TimeoutTimer(Consumer<Runnable> launcher, boolean threaded) {
        this.launcher = launcher;
        this.started = threaded ? new AtomicBoolean() : null;
        int wanted = Math.min(64, 2 * Runtime.getRuntime().availableProcessors());
        int count = Integer.highestOneBit(Math.max(1, wanted - 1)) << 1;
        this.stripes = new Stripe[count];
        for (int i = 0; i < count; i++) {
            stripes[i] = new Stripe();
        }
        this.mask = count - 1;
    }

    /** A lane for entries of {@code durationNanos}; create one per policy and reuse it. */
    Lane lane(long durationNanos) {
        return new Lane(this, durationNanos, false);
    }

    /** A lane whose entries are scheduled with their start time ({@link #schedule(TimerEntry, Lane, long)}). */
    Lane preciseLane(long durationNanos) {
        return new Lane(this, durationNanos, true);
    }

    /**
     * Fires {@code entry} on a new virtual thread once {@code lane}'s duration has passed, unless it is cancelled
     * first.
     */
    void schedule(TimerEntry entry, Lane lane) {
        if (lane.precise) {
            throw new IllegalArgumentException("A precise lane needs the start time");
        }
        push(entry, lane);
    }

    /**
     * Fires {@code entry} on a new virtual thread once {@code lane}'s duration has passed since {@code startNanos}
     * ({@code System.nanoTime()} on the shared timer), unless it is cancelled first. For a precise lane.
     */
    void schedule(TimerEntry entry, Lane lane, long startNanos) {
        if (!lane.precise) {
            throw new IllegalArgumentException("Only a precise lane takes a start time");
        }
        entry.deadline = startNanos + lane.durationNanos; // published with the entry by the inbox push
        push(entry, lane);
    }

    private void push(TimerEntry entry, Lane lane) {
        if (lane.timer != this) {
            throw new IllegalArgumentException("The lane belongs to another timer");
        }
        if (!lane.registered) {
            register(lane); // once per lane (a race only repeats it, harmlessly)
        }
        entry.lane = lane;
        entry.contextLoader = Threads.callers(); // published with the entry by the inbox push
        stripe().push(Heads.INBOX, entry);
    }

    /** Called by {@link TimerEntry#cancel()} after {@code SCHEDULED → CANCELLED}. */
    void cancelled(TimerEntry entry) {
        stripe().push(Heads.CANCELLED, entry);
    }

    private Stripe stripe() {
        return stripes[(int) Thread.currentThread().threadId() & mask];
    }

    private void register(Lane lane) {
        long wanted = Math.clamp(lane.durationNanos / 16, MIN_TICK, MAX_TICK);
        long before = tick.getAndAccumulate(wanted, Math::min);
        lane.registered = true;
        AtomicBoolean once = started;
        if (once != null && !once.get() && once.compareAndSet(false, true)) {
            Thread timer = Threads.daemon("resilience4cata-timeout-async", this::run);
            thread = timer;
            timer.start();
        } else if (wanted < before) {
            Thread timer = thread;
            if (timer != null) {
                LockSupport.unpark(timer); // start using the shorter tick now, not after the current one
            }
        }
    }

    /** The current tick length. */
    long tick() {
        return tick.get();
    }

    // java:S2189: the daemon timer thread runs for the life of the JVM.
    // java:S1181: nothing may stop the timer thread, or pending async timeouts would never fire.
    @SuppressWarnings({"java:S2189", "java:S1181"})
    private void run() {
        while (true) {
            try {
                tick(System.nanoTime());
            } catch (Throwable e) {
                // Keep timing out calls whatever happens; nothing here should throw.
            }
            LockSupport.parkNanos(nextWait(System.nanoTime()));
        }
    }

    /**
     * How long the timer thread parks after a tick: one tick, or less if a precise lane's head is due sooner (but
     * at least a sixteenth of a tick).
     */
    long nextWait(long now) {
        long wait = tick.get();
        if (preciseDue) {
            wait = Math.clamp(preciseDeadline - now, wait / 16, wait);
        }
        return wait;
    }

    /** One tick at time {@code now}: unlink cancelled entries, schedule new ones, fire expired ones. */
    void tick(long now) {
        drainCancelled();
        drainNew(now);
        expire(now);
    }

    /** Step 1 of a tick; separate for tests that replay races. */
    void drainCancelled() {
        for (Stripe stripe : stripes) {
            TimerEntry entry = stripe.take(Heads.CANCELLED);
            while (entry != null) {
                TimerEntry next = entry.stackNext;
                entry.stackNext = null;
                unlink(entry);
                entry = next;
            }
        }
        linkedSnapshot = linked;
    }

    /** Step 2 of a tick; separate for tests that replay races. */
    void drainNew(long now) {
        for (Stripe stripe : stripes) {
            TimerEntry entry = stripe.take(Heads.INBOX); // newest first
            TimerEntry precise = null; // this inbox's precise entries, oldest first, linked through next
            while (entry != null) {
                TimerEntry next = entry.stackNext;
                entry.stackNext = null; // before the CAS: a canceller may reuse the link once it sees SCHEDULED
                if (entry.tryMove(TimerEntry.NEW, TimerEntry.SCHEDULED)) {
                    Lane lane = entry.lane;
                    if (lane.precise) {
                        entry.next = precise; // the deadline was set from the caller's start
                        precise = entry;
                    } else {
                        entry.deadline = now + lane.durationNanos;
                        append(lane, entry);
                    }
                } // else cancelled before we saw it: drop it
                entry = next;
            }
            while (precise != null) {
                TimerEntry next = precise.next;
                append(precise.lane, precise);
                precise = next;
            }
        }
        linkedSnapshot = linked;
    }

    /** Step 3 of a tick; separate for tests that replay races. */
    // java:S3776: one pass over the lanes per tick, kept whole on the timer thread.
    // java:S1181: a launcher failure of any kind must not stop the other timeouts from firing.
    @SuppressWarnings({"java:S3776", "java:S1181"})
    void expire(long now) {
        boolean due = false;
        long earliest = 0;
        for (int i = active.size() - 1; i >= 0; i--) {
            Lane lane = active.get(i);
            for (TimerEntry head = lane.head; head != null && now - head.deadline >= 0; head = lane.head) {
                unlink(head);
                if (head.tryMove(TimerEntry.SCHEDULED, TimerEntry.FIRED)) {
                    try {
                        launcher.accept(head);
                    } catch (Throwable e) {
                        // Could not start a thread; keep firing the others.
                    }
                } // else cancelled meanwhile: it is on a cancelled stack, and unlink is idempotent
            }
            TimerEntry head = lane.head;
            if (head == null) {
                lane.active = false;
                Lane last = active.remove(active.size() - 1);
                if (last != lane) {
                    active.set(i, last);
                }
            } else if (lane.precise && (!due || head.deadline - earliest < 0)) {
                due = true;
                earliest = head.deadline;
            }
        }
        preciseDue = due;
        preciseDeadline = earliest;
        linkedSnapshot = linked;
    }

    private void append(Lane lane, TimerEntry entry) {
        TimerEntry tail = lane.tail;
        entry.prev = tail;
        entry.next = null;
        if (tail == null) {
            lane.head = entry;
        } else {
            tail.next = entry;
        }
        lane.tail = entry;
        entry.linked = true;
        linked++;
        if (!lane.active) {
            lane.active = true;
            active.add(lane);
        }
    }

    private void unlink(TimerEntry entry) {
        if (!entry.linked) {
            return; // already fired, or already unlinked
        }
        Lane lane = entry.lane;
        TimerEntry prev = entry.prev;
        TimerEntry next = entry.next;
        if (prev == null) {
            lane.head = next;
        } else {
            prev.next = next;
        }
        if (next == null) {
            lane.tail = prev;
        } else {
            next.prev = prev;
        }
        entry.prev = null;
        entry.next = null;
        entry.linked = false;
        linked--;
    }

    /**
     * Entries the timer still holds: scheduled ones, as of its last step, plus those waiting in inboxes
     * or cancelled stacks. Racy against a running timer thread; for tests and diagnostics only.
     */
    int pending() {
        int count = linkedSnapshot;
        for (Stripe stripe : stripes) {
            for (TimerEntry entry = stripe.peek(Heads.INBOX); entry != null; entry = entry.stackNext) {
                count++;
            }
        }
        return count;
    }

    /** Entries of one duration, in deadline order. Belongs to one timer. */
    static final class Lane {
        final TimeoutTimer timer;
        final long durationNanos;
        /** Entries carry their own deadline, from the start time their caller passed. */
        final boolean precise;
        boolean registered; // written by callers; a stale false only repeats the registration
        // Owned by the timer thread.
        @Nullable TimerEntry head;
        @Nullable TimerEntry tail;
        boolean active;

        Lane(TimeoutTimer timer, long durationNanos, boolean precise) {
            this.timer = timer;
            this.durationNanos = durationNanos;
            this.precise = precise;
        }
    }

    /** Padding before the stack heads, so that they do not share a cache line with what precedes them. */
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

    private static class Heads extends PadBefore {
        static final VarHandle INBOX;
        static final VarHandle CANCELLED;

        static {
            try {
                MethodHandles.Lookup lookup = MethodHandles.lookup();
                INBOX = lookup.findVarHandle(Heads.class, "inbox", TimerEntry.class);
                CANCELLED = lookup.findVarHandle(Heads.class, "cancelled", TimerEntry.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        // java:S3077: both are lock-free stack heads, pushed and taken only by compare-and-set through their
        // VarHandles; a TimerEntry's own state is a volatile, CASed field too.
        // java:S1845: the field and its VarHandle share a name, the usual VarHandle naming convention.
        @SuppressWarnings({"unused", "java:S3077", "java:S1845"}) // accessed through INBOX
        volatile @Nullable TimerEntry inbox;
        @SuppressWarnings({"unused", "java:S3077", "java:S1845"}) // accessed through CANCELLED
        volatile @Nullable TimerEntry cancelled;
    }

    /**
     * Two lock-free stacks (many pushers, one taker). Superclass fields are laid out first, so the heads sit
     * between two blocks of padding.
     */
    @SuppressWarnings("unused")
    private static final class Stripe extends Heads {
        long q0;
        long q1;
        long q2;
        long q3;
        long q4;
        long q5;
        long q6;
        long q7;

        void push(VarHandle head, TimerEntry entry) {
            TimerEntry current = (TimerEntry) head.getOpaque(this);
            while (true) {
                entry.stackNext = current;
                // Release publishes the entry's fields (lane, stackNext) to the timer, which takes the
                // stack with an acquire. No acquire is needed here: we read nothing through the witness.
                TimerEntry witness = (TimerEntry) head.compareAndExchangeRelease(this, current, entry);
                if (witness == current) {
                    return;
                }
                current = witness;
            }
        }

        @Nullable TimerEntry take(VarHandle head) {
            if (head.getOpaque(this) == null) {
                return null; // nothing to take: do not write the line
            }
            return (TimerEntry) head.getAndSetAcquire(this, (TimerEntry) null);
        }

        @Nullable TimerEntry peek(VarHandle head) {
            return (TimerEntry) head.getAcquire(this);
        }
    }
}
