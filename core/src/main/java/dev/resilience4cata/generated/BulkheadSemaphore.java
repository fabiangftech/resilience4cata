package dev.resilience4cata.generated;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;

/**
 * The permits of a bulkhead that may wait ({@code maxWait > 0}): a non-fair semaphore like
 * {@link java.util.concurrent.Semaphore}, whose permits are counted in a padded counter of their own with
 * the weakest atomics that are correct, while {@link AbstractQueuedSynchronizer} only queues and parks the
 * threads that wait (virtual threads included, without pinning).
 *
 * <p>Taking a free permit without waiting ({@link #tryAcquire()}) is the no-wait bulkhead's path: an opaque
 * read and one acquire compare-and-set, nothing written when there is no permit. A waiting thread goes
 * through the queue, whose {@link #tryAcquireShared} uses volatile accesses. {@link #release()} gives the
 * permit back with a volatile add and then looks for a queued thread to wake: a store-buffering pair with
 * the queued thread, which publishes that it waits and then tries the counter again before it parks, so one
 * of the two always sees the other and no wake-up is lost. {@code Semaphore} uses full-fence
 * compare-and-set loops on both sides, which cost more on ARM.
 */
final class BulkheadSemaphore extends AbstractQueuedSynchronizer {

    private static final long serialVersionUID = 1L;

    private final int permits;
    private final transient Counter inUse = new Counter();

    BulkheadSemaphore(int permits) {
        this.permits = permits;
    }

    /** Takes a permit if one is free now; never waits and writes nothing when none is. */
    boolean tryAcquire() {
        return inUse.tryIncrement(permits);
    }

    /** Takes a permit, waiting up to {@code timeout} for one. */
    boolean tryAcquire(long timeout, TimeUnit unit) throws InterruptedException {
        return tryAcquire() || tryAcquireSharedNanos(1, unit.toNanos(timeout));
    }

    /** Takes a permit, waiting as long as it takes. */
    void acquire() throws InterruptedException {
        if (!tryAcquire()) {
            acquireSharedInterruptibly(1);
        }
    }

    /** Gives a permit back and wakes a queued thread if there is one. */
    void release() {
        releaseShared(1);
    }

    /** Permits free now; racy, for monitoring. */
    int availablePermits() {
        return permits - inUse.get();
    }

    @Override
    protected int tryAcquireShared(int ignored) {
        int current = inUse.get();
        while (current < permits) {
            int witness = inUse.compareAndExchange(current, current + 1);
            if (witness == current) {
                return permits - current - 1; // > 0: the next queued thread may take one too
            }
            current = witness;
        }
        return -1;
    }

    @Override
    protected boolean tryReleaseShared(int ignored) {
        inUse.decrement();
        return true;
    }

    /** Padding before the counter, so that it does not share a cache line with what precedes it in memory. */
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

    /** Superclass fields are laid out first, so the counter sits between two blocks of padding. */
    @SuppressWarnings("unused")
    private static final class Counter extends Value {
        long q0;
        long q1;
        long q2;
        long q3;
        long q4;
        long q5;
        long q6;
        long q7;

        /** The fast path: opaque read, acquire compare-and-set (it guards no memory but the slot itself). */
        boolean tryIncrement(int max) {
            int current = (int) VALUE.getOpaque(this);
            while (current < max) {
                int witness = (int) VALUE.compareAndExchangeAcquire(this, current, current + 1);
                if (witness == current) {
                    return true;
                }
                current = witness;
            }
            return false;
        }

        int compareAndExchange(int expected, int next) {
            return (int) VALUE.compareAndExchange(this, expected, next);
        }

        /** Volatile, not release: the half of the store-buffering pair with a queued thread. */
        void decrement() {
            VALUE.getAndAdd(this, -1);
        }

        int get() {
            return (int) VALUE.getVolatile(this);
        }
    }
}
