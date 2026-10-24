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
 * past {@code start + timeout + tick}: never early, at most about two ticks late.
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
    private final ThreadLocal<ThreadScopes> local = ThreadLocal.withInitial(this::register);
    private final AtomicLong tick = new AtomicLong(MAX_TICK);
    @SuppressWarnings("unused") // accessed through NOW
    private volatile long now;

    /** The shared watchdog, started on first use. */
    static Watchdog shared() {
        return Shared.INSTANCE;
    }

    private static final class Shared {
        static final Watchdog INSTANCE = start();

        private static Watchdog start() {
            Watchdog watchdog = new Watchdog(System.nanoTime());
            Thread.ofPlatform().daemon().name("resilience4cata-timeout").start(watchdog::run);
            return watchdog;
        }
    }

    /** Creates a watchdog that only moves when {@link #tick(long)} is called; used directly by tests. */
    Watchdog(long now) {
        this.now = now;
    }

    /** Makes the tick short enough for a timeout of this length. */
    void requireTimeout(long timeoutNanos) {
        long wanted = Math.max(MIN_TICK, Math.min(MAX_TICK, timeoutNanos / 16));
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

    private void run() {
        while (true) {
            try {
                tick(System.nanoTime());
            } catch (Throwable e) {
                // Keep timing out calls whatever happens; nothing here should throw.
            }
            LockSupport.parkNanos(tick.get());
        }
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
                scope = new TimeoutPolicy.Scope(thread);
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
