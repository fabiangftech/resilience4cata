package dev.resilience4cata.generated;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * One daemon thread that times out calls for the whole JVM.
 *
 * <p>Timed calls never read the clock and never write shared memory. Each thread owns a small stack
 * of {@link TimeoutPolicy.Scope} objects, registered here the first time the thread makes a timed
 * call and reused afterwards. A call writes its start time, using this watchdog's coarse clock, into
 * its own scope. Every tick the watchdog reads the real clock once, publishes it as the coarse clock,
 * and checks every registered scope; a call that has run past its timeout gets its thread interrupted.
 *
 * <p>The tick is {@code timeout / 16} of the shortest timeout in use, between 1 ms and 50 ms. The watchdog
 * wakes every three quarters of a tick.
 *
 * <p>The coarse clock advances by the real time elapsed between two ticks, but by at most one step
 * ({@link #advance}). Without that cap, a watchdog that wakes up late (an overloaded machine, a long GC
 * pause) would have left one coarse value current for that long, so calls started during that time would
 * look older than they are and could be interrupted early. A call's start is therefore at most one step
 * old, and a call is interrupted only when the coarse clock is past {@code start + timeout + slack}, where
 * the slack is the step the coarse clock took from {@code start} (at least one tick): never early. The
 * watchdog remembers its last {@value #STEPS} steps and works out a call's slack the first time it sees the
 * call running ({@link #slack(long)}), which is at most two or three ticks after the call read its start.
 *
 * <p>The step is one tick on a machine that wakes the watchdog on time. Some do not: a sleep of 0.75 ms
 * lasted about 1.9 ms on GitHub's Windows runners and 4 ms on its macOS virtual machines, so a coarse clock
 * capped at one tick per wake-up ran 1.5 to 3.7 times slower than the real one, and timeouts fired that
 * much late (200 ms after 760 ms) once a short timeout had brought the tick down to 1 ms. So the step is the
 * larger of the tick and the second-longest of the last eight sleeps: a machine that always sleeps longer
 * raises it (and with it the slack of the calls that start meanwhile, so their timeouts end at most a few such
 * sleeps late), while one late wake-up, which is what the cap is for, does not: it only makes the coarse clock
 * fall behind, which delays timeouts by that much and never brings them forward.
 */
final class Watchdog {

    private static final long MIN_TICK = 1_000_000L;
    private static final long MAX_TICK = 50_000_000L;
    /** How many recent sleeps {@link #advance} looks at; the longest of them is left out (an outlier). */
    private static final int SLEEPS = 8;
    /** How many recent steps of the coarse clock {@link #slack(long)} can look up. */
    private static final int STEPS = 16;
    /** {@link #slack(long)}'s answer while the coarse clock has taken no step from a start yet. */
    static final long NOT_YET = -1;
    private static final VarHandle NOW;

    static {
        try {
            NOW = MethodHandles.lookup().findVarHandle(Watchdog.class, "now", long.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final ConcurrentLinkedQueue<ThreadScopes> threads = new ConcurrentLinkedQueue<>();
    // java:S5164: a thread keeps its scopes for its whole life, so that timed calls allocate nothing; the entry
    // goes with the thread, and tick() unlinks the scopes of threads that are no longer alive.
    @SuppressWarnings("java:S5164")
    private final ThreadLocal<ThreadScopes> local = ThreadLocal.withInitial(this::register);
    private final AtomicLong tick;
    // java:S1845: the field and its VarHandle share a name, the usual VarHandle naming convention.
    @SuppressWarnings({"unused", "java:S1845"}) // accessed through NOW
    private volatile long now;
    // Owned by the watchdog thread: the last real clock reading, and the coarse clock built from it.
    private long lastReal;
    private long coarse;
    // Owned by the thread that calls advance() and tick(): the watchdog thread, or a test. The real time between
    // the last SLEEPS ticks; the last STEPS steps of the coarse clock (the coarse time each step started from, and
    // its length), oldest at nextStep once full; and the largest step ever, for a call older than all of them.
    private final long[] sleeps = new long[SLEEPS];
    private int nextSleep;
    private final long[] stepFrom = new long[STEPS];
    private final long[] stepLength = new long[STEPS];
    private int nextStep;
    private int steps;
    private long largestStep;

    /** The shared watchdog, started on first use. */
    static Watchdog shared() {
        return Shared.INSTANCE;
    }

    private static final class Shared {
        static final Watchdog INSTANCE = start();

        private static Watchdog start() {
            Watchdog watchdog = new Watchdog(0);
            watchdog.lastReal = System.nanoTime();
            Threads.daemon("resilience4cata-timeout", watchdog::run).start();
            return watchdog;
        }
    }

    /** Creates a watchdog that only moves when {@link #tick(long)} is called; used directly by tests. */
    Watchdog(long now) {
        this(now, MAX_TICK);
    }

    private Watchdog(long now, long tick) {
        this.now = now;
        this.coarse = now;
        this.tick = new AtomicLong(tick);
    }

    /**
     * Creates a watchdog for a manual {@code Ticker}: it moves only when {@link #tick(long)} is called
     * with the ticker's exact time, so its tick, and with it the slack added to every timeout, is zero.
     */
    static Watchdog manual(long now) {
        return new Watchdog(now, 0);
    }

    /** Makes the tick short enough for a timeout of this length. */
    void requireTimeout(long timeoutNanos) {
        long wanted = Math.clamp(timeoutNanos / 16, MIN_TICK, MAX_TICK);
        tick.accumulateAndGet(wanted, Math::min);
    }

    /** The coarse clock: the real clock as of the last tick. */
    long now() {
        return (long) NOW.getOpaque(this);
    }

    /** The current tick length: the watchdog wakes every three quarters of it. */
    long tick() {
        return tick.getOpaque();
    }

    /** The calling thread's scopes. */
    ThreadScopes scopes() {
        return local.get();
    }

    private ThreadScopes register() {
        ThreadScopes scopes = new ThreadScopes(Thread.currentThread());
        threads.add(scopes);
        return scopes;
    }

    // java:S2189: the daemon watchdog thread runs for the life of the JVM.
    // java:S1181: nothing may stop the watchdog thread, or timed calls would never be interrupted.
    @SuppressWarnings({"java:S2189", "java:S1181"})
    private void run() {
        while (true) {
            try {
                tick(advance(System.nanoTime()));
            } catch (Throwable e) {
                // Keep timing out calls whatever happens; nothing here should throw.
            }
            // Sleep for three quarters of a tick: an ordinary wake-up delay then stays within the tick,
            // so the cap in advance() does not make the coarse clock fall behind on a healthy machine.
            LockSupport.parkNanos(tick.get() / 4 * 3);
        }
    }

    /**
     * The coarse time to publish when the real clock reads {@code realNanos}: the previous coarse time
     * plus the real time elapsed, capped at one tick (see the class comment). Called by the watchdog
     * thread only.
     */
    long advance(long realNanos) {
        long elapsed = Math.max(realNanos - lastReal, 0L);
        lastReal = realNanos;
        sleeps[nextSleep] = elapsed;
        nextSleep = (nextSleep + 1) % SLEEPS;
        long step = Math.min(Math.clamp(elapsed, 0L, Math.max(tick.get(), secondLongestSleep())), MAX_TICK);
        largestStep = Math.max(largestStep, step);
        stepFrom[nextStep] = coarse;
        stepLength[nextStep] = step;
        nextStep = (nextStep + 1) % STEPS;
        steps = Math.min(steps + 1, STEPS);
        coarse += step;
        return coarse;
    }

    private long secondLongestSleep() {
        long longest = 0;
        long second = 0;
        for (long sleep : sleeps) {
            if (sleep > longest) {
                second = longest;
                longest = sleep;
            } else if (sleep > second) {
                second = sleep;
            }
        }
        return second;
    }

    /**
     * How long past its timeout a call that read {@code start} must be before it is interrupted: the step the
     * coarse clock took from {@code start}, since the call's real start can be that much later (every step taken
     * since is counted too, which only adds), and at least one tick (as before; a test's watchdog takes no steps).
     * A start older than every remembered step gets the largest step ever. {@link #NOT_YET} while no step was
     * taken from {@code start} (the coarse clock still reads it, so no call that read it can be overdue). Called
     * by the {@link #tick} thread.
     */
    long slack(long start) {
        long slack = tick.get();
        if (steps == 0) {
            return slack; // a test's watchdog, moved only by tick(long)
        }
        int oldest = steps < STEPS ? 0 : nextStep;
        if (start - stepFrom[oldest] < 0) {
            return Math.max(slack, largestStep);
        }
        boolean stepped = false;
        for (int i = 0; i < steps; i++) {
            if (stepFrom[i] - start >= 0) {
                slack = Math.max(slack, stepLength[i]);
                stepped = true;
            }
        }
        return stepped ? slack : NOT_YET;
    }

    /** Publishes {@code nanoTime} as the coarse clock and interrupts calls that ran out of time. */
    void tick(long nanoTime) {
        NOW.setOpaque(this, nanoTime);
        for (Iterator<ThreadScopes> it = threads.iterator(); it.hasNext(); ) {
            ThreadScopes scopes = it.next();
            if (!scopes.thread.isAlive()) {
                it.remove();
                continue;
            }
            for (TimeoutPolicy.Scope scope : scopes.stack()) {
                if (scope != null) {
                    scope.expireIfOverdue(nanoTime, this);
                }
            }
        }
    }

    /**
     * Forgets the calling thread's scopes in this watchdog. For stress tests, which create a watchdog per
     * jcstress state: without it, each thread's {@code ThreadLocalMap} holds one entry per live state (tens of
     * thousands), and lookups slowed a thread down until jcstress reported the test as hung.
     */
    void forgetCurrentThreadForTest() {
        local.remove();
    }

    /** Number of threads with registered scopes; used by tests. */
    int registeredThreads() {
        return threads.size();
    }

    /** The scopes of one thread, as a stack because timed calls can be nested. Only the owner pushes and pops. */
    static final class ThreadScopes {
        final Thread thread;
        // java:S3077: only the owner thread writes; a grown array is published whole through this field, and each
        // Scope keeps its own state in a volatile, CASed field that the watchdog reads.
        @SuppressWarnings("java:S3077")
        private volatile TimeoutPolicy.Scope[] stack = new TimeoutPolicy.Scope[2];
        private int depth;

        ThreadScopes(Thread thread) {
            this.thread = thread;
        }

        TimeoutPolicy.Scope[] stack() {
            return stack;
        }

        TimeoutPolicy.Scope push() {
            TimeoutPolicy.Scope[] current = stack;
            if (depth == current.length) {
                TimeoutPolicy.Scope[] grown = java.util.Arrays.copyOf(current, depth * 2);
                stack = grown;
                current = grown;
            }
            TimeoutPolicy.Scope scope = current[depth];
            if (scope == null) {
                scope = new TimeoutPolicy.Scope(this);
                current[depth] = scope;
                stack = current; // publish the new scope to the watchdog
            }
            depth++;
            return scope;
        }

        void pop() {
            depth--;
        }
    }
}
