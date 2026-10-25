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
 * <p>The tick is {@code timeout / 16} of the shortest timeout in use, between 1 ms and 50 ms. Because
 * a call's start time can be up to one tick old, a call is interrupted only when the coarse clock is
 * past {@code start + timeout + tick}: never early, at most about two ticks late (plus any delay in
 * the watchdog's own wake-ups). The watchdog wakes every three quarters of a tick.
 *
 * <p>The coarse clock advances by the real time elapsed between two ticks, but by at most one tick
 * per tick ({@link #advance}). Without that cap, a watchdog that wakes up late (an overloaded machine,
 * a long GC pause) would have left one coarse value current for longer than a tick, so calls started
 * during that time would look older than they are and could be interrupted early. With it, two
 * consecutive coarse values are never more than a tick apart, so {@code start + tick} is never before
 * the real start; a late wake-up only makes the coarse clock fall behind, which delays timeouts by that
 * much and never brings them forward.
 */
final class Watchdog {

    private static final long MIN_TICK = 1_000_000L;
    private static final long MAX_TICK = 50_000_000L;
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

    /** The shared watchdog, started on first use. */
    static Watchdog shared() {
        return Shared.INSTANCE;
    }

    private static final class Shared {
        static final Watchdog INSTANCE = start();

        private static Watchdog start() {
            Watchdog watchdog = new Watchdog(0);
            watchdog.lastReal = System.nanoTime();
            Thread.ofPlatform().daemon().name("resilience4cata-timeout").start(watchdog::run);
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

    /** The current tick length, which is also how stale {@link #now()} can be. */
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
        long elapsed = realNanos - lastReal;
        lastReal = realNanos;
        coarse += Math.clamp(elapsed, 0L, tick.get());
        return coarse;
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
                    scope.expireIfOverdue(nanoTime);
                }
            }
        }
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
