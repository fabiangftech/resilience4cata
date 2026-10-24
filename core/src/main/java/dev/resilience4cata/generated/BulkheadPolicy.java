package dev.resilience4cata.generated;

import dev.resilience4cata.BulkheadConfig;
import dev.resilience4cata.BulkheadFullException;
import dev.resilience4cata.BulkheadHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
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
 *
 * <p>Asynchronous calls ({@link #executeAsync}) never block while waiting: they queue in
 * {@link BulkheadWaiters} and receive a released slot directly, oldest first. Synchronous and
 * asynchronous callers share the same slots. When both kinds wait, released slots alternate between
 * the oldest asynchronous waiter and the synchronous callers blocked on the semaphore; a new
 * synchronous caller can still take a free permit ahead of everyone (the semaphore is non-fair), while
 * a new asynchronous caller queues behind the asynchronous callers already waiting.
 */
public final class BulkheadPolicy implements BulkheadHandle {

    private final String id;
    private final BulkheadConfig config;
    private final boolean enabled;
    private final int maxConcurrent;
    private final long maxWaitNanos;
    private final PaddedCounter inUse = new PaddedCounter();
    private final @Nullable Semaphore semaphore;
    private final @Nullable BulkheadWaiters waiters;
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
        this.waiters = maxWaitNanos > 0 ? new BulkheadWaiters() : null;
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

    /**
     * Frees the slot taken by a successful {@link #tryEnter()}.
     *
     * <p>With {@code maxWait > 0}, the permit is released first and the number of asynchronous waiters
     * read afterwards; an enqueuing waiter does the opposite (counts itself, then tries to take a
     * permit). Both are volatile accesses, so at least one side sees the other and a slot is never
     * left free while a waiter stays queued. The read of the counter adds no fence: the semaphore's
     * release already ends with a volatile read. When nobody waits asynchronously, that read is the
     * only extra work.
     */
    public void exit() {
        if (!enabled) {
            return;
        }
        Semaphore waiting = semaphore;
        if (waiting == null) {
            inUse.decrement();
        } else {
            waiting.release();
            BulkheadWaiters queued = waiters;
            if (queued != null && queued.count() > 0) {
                handOff(waiting, queued);
            }
        }
    }

    /**
     * A slot was released while asynchronous callers wait. When synchronous callers are blocked on the
     * semaphore as well, every other release is left to them; otherwise the slot goes to the oldest
     * asynchronous waiter.
     */
    private void handOff(Semaphore waiting, BulkheadWaiters queued) {
        if (waiting.hasQueuedThreads() && queued.yieldToSync()) {
            return;
        }
        drain(waiting, queued);
    }

    /**
     * Gives free permits to the oldest asynchronous waiters. A permit is taken from the semaphore before
     * a waiter is claimed, so if someone else (a blocked synchronous caller, a new caller, another
     * drain) takes it first, nothing is handed off and the waiters stay queued: whoever holds the slot
     * will run this again when it releases it. A permit taken while no waiter can be claimed is given
     * back; the queue is then checked again, since a waiter may have been queued in between and failed
     * to take the permit while it was held here.
     */
    private void drain(Semaphore waiting, BulkheadWaiters queued) {
        while (queued.count() > 0) {
            if (!waiting.tryAcquire()) {
                return;
            }
            BulkheadWaiters.Waiter<?> waiter = queued.claimOldest();
            if (waiter != null) {
                grant(waiter);
                continue;
            }
            waiting.release();
            if (queued.isEmpty()) {
                return;
            }
            Thread.onSpinWait();
        }
    }

    /** Runs a waiter's call, which now holds a slot, on a new virtual thread: never on the releasing thread. */
    private <T> void grant(BulkheadWaiters.Waiter<T> waiter) {
        waiter.cancelTimer();
        Thread.ofVirtual().name("resilience4cata-bulkhead").start(() -> run(waiter.call, waiter.result));
    }

    /** Number of asynchronous callers waiting for a slot; for tests. */
    int queuedCalls() {
        BulkheadWaiters queued = waiters;
        return queued != null ? queued.count() : 0;
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

    /**
     * Runs an asynchronous call in the bulkhead: the slot is held until the call's stage completes, so
     * the limit applies to calls in flight. A full bulkhead is a failed future.
     *
     * <p>With {@code maxWait > 0}, the wait never blocks: a call that finds the bulkhead full (or other
     * asynchronous callers already waiting, so that they are served in order) is queued and its future
     * returned at once. When a slot is released, by an asynchronous or a synchronous call, it is handed
     * directly to the oldest waiter, whose call then starts on a new virtual thread. A waiter that is
     * not served within {@code maxWait} fails with {@link #rejection()} and can no longer receive a slot.
     *
     * @param call the next layer
     * @param <T> the result type
     * @return a future that completes with the call's outcome
     */
    public <T> CompletableFuture<T> executeAsync(AsyncCall<T> call) {
        BulkheadWaiters queued = waiters;
        Semaphore waiting = semaphore;
        if (!enabled || queued == null || waiting == null) {
            if (!tryEnter()) {
                return CompletableFuture.failedFuture(rejection());
            }
            CompletableFuture<T> result = new CompletableFuture<>();
            run(call, result);
            return result;
        }
        if (queued.count() == 0 && waiting.tryAcquire()) {
            CompletableFuture<T> result = new CompletableFuture<>();
            run(call, result);
            return result;
        }
        BulkheadWaiters.Waiter<T> waiter = new BulkheadWaiters.Waiter<>(call, queued, this);
        queued.add(waiter);
        waiter.setTimer(AsyncSupport.later(maxWaitNanos, waiter));
        drain(waiting, queued);
        return waiter.result;
    }

    /**
     * Test seam: the first step of a queued {@link #executeAsync}, without its timer and without the
     * drain that follows, so that tests can replay interleavings. Requires {@code maxWait > 0}.
     */
    <T> BulkheadWaiters.Waiter<T> enqueueForTest(AsyncCall<T> call) {
        BulkheadWaiters queued = Objects.requireNonNull(waiters);
        BulkheadWaiters.Waiter<T> waiter = new BulkheadWaiters.Waiter<>(call, queued, this);
        queued.add(waiter);
        return waiter;
    }

    /** Test seam: the drain that ends a queued {@link #executeAsync}. */
    void drainForTest() {
        drain(Objects.requireNonNull(semaphore), Objects.requireNonNull(waiters));
    }

    /** Calls the next layer holding a slot, and frees the slot when its stage completes. */
    private <T> void run(AsyncCall<T> call, CompletableFuture<T> result) {
        AsyncSupport.call(call).whenComplete((value, failure) -> {
            exit();
            if (failure == null) {
                result.complete(value);
            } else {
                result.completeExceptionally(AsyncSupport.unwrap(failure));
            }
        });
    }
}
