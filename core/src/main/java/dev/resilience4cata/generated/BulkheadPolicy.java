package dev.resilience4cata.generated;

import dev.resilience4cata.BulkheadConfig;
import dev.resilience4cata.BulkheadFullException;
import dev.resilience4cata.BulkheadHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * Bulkhead for generated code: limits how many calls run at the same time.
 *
 * <pre>{@code
 * if (!bulkhead.tryEnter()) throw bulkhead.rejection();
 * try {
 *     return delegate.call();
 * } finally {
 *     bulkhead.exit();
 * }
 * }</pre>
 *
 * <p>Without waiting ({@code maxWait = 0}), slots are counted with a compare-and-set loop on one
 * counter. A full bulkhead only reads that counter, so rejections under overload do not add
 * contention. The counter lives in its own padded object, so that the writes of every call do not
 * share a cache line with the configuration fields that every call reads. With waiting, a non-fair
 * {@link Semaphore} is used instead: same cost when a slot is free, and it parks virtual threads
 * without pinning them.
 */
public final class BulkheadPolicy implements BulkheadHandle {

    private final String id;
    private final BulkheadConfig config;
    private final boolean enabled;
    private final int maxConcurrent;
    private final long maxWaitNanos;
    private final PaddedCounter inUse = new PaddedCounter();
    private final @Nullable Semaphore semaphore;
    private final BulkheadFullException rejection;

    /**
     * Creates a bulkhead.
     *
     * @param id the bulkhead's id, used in the rejection message
     * @param config the configuration
     */
    public BulkheadPolicy(String id, BulkheadConfig config) {
        this.id = id;
        this.config = config;
        this.enabled = config.enabled();
        this.maxConcurrent = config.maxConcurrent();
        this.maxWaitNanos = config.maxWait().toNanos();
        this.semaphore = maxWaitNanos > 0 ? new Semaphore(maxConcurrent, false) : null;
        this.rejection = new BulkheadFullException(id);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public BulkheadConfig config() {
        return config;
    }

    @Override
    public int concurrentCalls() {
        Semaphore waiting = semaphore;
        return waiting != null ? maxConcurrent - waiting.availablePermits() : inUse.get();
    }

    /** Returns the preallocated exception to throw when {@link #tryEnter()} returns {@code false}. */
    public BulkheadFullException rejection() {
        return rejection;
    }

    /**
     * Takes a slot, waiting up to {@code maxWait} if the bulkhead is full.
     *
     * @return {@code true} if the call may run; it must then call {@link #exit()}. {@code false} if the
     *     bulkhead stayed full, or the thread was interrupted while waiting (the interrupt flag is set
     *     again).
     */
    public boolean tryEnter() {
        if (!enabled) {
            return true;
        }
        Semaphore waiting = semaphore;
        if (waiting == null) {
            return inUse.tryIncrement(maxConcurrent);
        }
        if (waiting.tryAcquire()) {
            return true;
        }
        try {
            return waiting.tryAcquire(maxWaitNanos, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Frees the slot taken by a successful {@link #tryEnter()}. */
    public void exit() {
        if (!enabled) {
            return;
        }
        Semaphore waiting = semaphore;
        if (waiting == null) {
            inUse.decrement();
        } else {
            waiting.release();
        }
    }

    /** Padding before the counter, so that it does not share a cache line with what precedes it in memory. */
    @SuppressWarnings("unused")
    private static class PadBefore {
        long p0, p1, p2, p3, p4, p5, p6, p7;
    }

    private static class Value extends PadBefore {
        static final VarHandle VALUE;

        static {
            try {
                VALUE = MethodHandles.lookup().findVarHandle(Value.class, "value", int.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        @SuppressWarnings("unused") // accessed through VALUE
        volatile int value;
    }

    /** Superclass fields are laid out first, so the counter sits between two blocks of padding. */
    @SuppressWarnings("unused")
    private static final class PaddedCounter extends Value {
        long q0, q1, q2, q3, q4, q5, q6, q7;

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

        void decrement() {
            VALUE.getAndAddRelease(this, -1);
        }

        int get() {
            return (int) VALUE.getVolatile(this);
        }
    }
}
