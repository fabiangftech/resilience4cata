package dev.resilience4cata.generated;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Future;
import org.jspecify.annotations.Nullable;

/**
 * The asynchronous callers waiting for a slot of one {@link BulkheadPolicy}, oldest first.
 *
 * <p>{@link #count()} is the number of waiters still {@linkplain Waiter#claim() unclaimed}. It is
 * incremented <em>before</em> a waiter is added to the queue and decremented by whoever claims it, so
 * it is never negative and is never zero while an unclaimed waiter is in the queue. It is the only
 * thing {@link BulkheadPolicy#exit()} reads when nobody waits asynchronously.
 */
@SuppressWarnings("unused") // padding
final class BulkheadWaiters extends BulkheadWaitersCount {

    /** Superclass fields are laid out first, so the counter sits between two blocks of padding. */
    long q0, q1, q2, q3, q4, q5, q6, q7;

    private final ConcurrentLinkedQueue<Waiter<?>> queue = new ConcurrentLinkedQueue<>();

    /** Turn counter for sharing slots with blocked synchronous callers; racy on purpose (a heuristic). */
    private int turn;

    /** Number of unclaimed waiters. A volatile read: see {@link BulkheadPolicy#exit()} for why. */
    int count() {
        return (int) COUNT.getVolatile(this);
    }

    /** Registers a waiter: counted first, then queued. */
    void add(Waiter<?> waiter) {
        COUNT.getAndAdd(this, 1);
        queue.offer(waiter);
    }

    /** Removes and claims the oldest waiter that is still unclaimed and whose future is not done; {@code null} if none. */
    @Nullable Waiter<?> claimOldest() {
        Waiter<?> waiter;
        while ((waiter = queue.poll()) != null) {
            if (waiter.claim()) {
                if (!waiter.result.isDone()) {
                    return waiter;
                }
                waiter.cancelTimer(); // cancelled by its caller while waiting: drop it
            }
        }
        return null;
    }

    boolean isEmpty() {
        return queue.isEmpty();
    }

    /** Whether this hand-off should leave the slot to a blocked synchronous caller: every other time. */
    boolean yieldToSync() {
        return (turn++ & 1) != 0;
    }

    /** An asynchronous call waiting for a slot. Its {@link #run()} is the timeout. */
    static final class Waiter<T> implements Runnable {

        private static final VarHandle STATE;
        private static final VarHandle TIMER;

        static {
            try {
                MethodHandles.Lookup lookup = MethodHandles.lookup();
                STATE = lookup.findVarHandle(Waiter.class, "state", int.class);
                TIMER = lookup.findVarHandle(Waiter.class, "timer", Future.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        private static final int WAITING = 0;
        private static final int CLAIMED = 1;

        final AsyncCall<T> call;
        final CompletableFuture<T> result = new CompletableFuture<>();
        private final BulkheadWaiters owner;
        private final BulkheadPolicy policy;

        @SuppressWarnings("unused") // accessed through STATE
        private volatile int state;

        @SuppressWarnings("unused") // accessed through TIMER
        private volatile @Nullable Future<?> timer;

        Waiter(AsyncCall<T> call, BulkheadWaiters owner, BulkheadPolicy policy) {
            this.call = call;
            this.owner = owner;
            this.policy = policy;
        }

        /**
         * Takes this waiter out of the waiting state. Exactly one caller succeeds: either the hand-off of a
         * slot or the timeout, never both, so a slot is never given to a waiter that already failed, and a
         * waiter that received a slot is never failed.
         */
        boolean claim() {
            if (STATE.compareAndSet(this, WAITING, CLAIMED)) {
                COUNT.getAndAdd(owner, -1);
                return true;
            }
            return false;
        }

        boolean isClaimed() {
            return (int) STATE.getVolatile(this) != WAITING;
        }

        void setTimer(@Nullable Future<?> scheduled) {
            TIMER.setVolatile(this, scheduled);
            if (scheduled != null && isClaimed()) {
                scheduled.cancel(false); // claimed before the timer was published: the claimer could not cancel it
            }
        }

        void cancelTimer() {
            Future<?> scheduled = (Future<?>) TIMER.getVolatile(this);
            if (scheduled != null) {
                scheduled.cancel(false);
            }
        }

        /** The timeout: fails the waiter with the bulkhead's rejection, unless a slot was handed to it first. */
        @Override
        public void run() {
            if (claim()) {
                owner.queue.remove(this); // so that waiters timing out on a stuck bulkhead do not pile up
                policy.rejected();
                result.completeExceptionally(policy.rejection());
            }
        }
    }
}

/** Padding before the counter, so that it does not share a cache line with what precedes it in memory. */
@SuppressWarnings("unused")
abstract class BulkheadWaitersPad {
    long p0, p1, p2, p3, p4, p5, p6, p7;
}

abstract class BulkheadWaitersCount extends BulkheadWaitersPad {
    static final VarHandle COUNT;

    static {
        try {
            COUNT = MethodHandles.lookup().findVarHandle(BulkheadWaitersCount.class, "count", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @SuppressWarnings("unused") // accessed through COUNT
    volatile int count;
}
