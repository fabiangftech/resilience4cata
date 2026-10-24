package dev.resilience4cata.generated;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.FORBIDDEN;

import dev.resilience4cata.BulkheadConfig;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.III_Result;

/**
 * {@link BulkheadPolicy.TickerWait}: the deadline of a synchronous bulkhead wait on a manual ticker,
 * racing with the waiting thread and with releases. The waiting thread's blocking {@code acquire()} is
 * replaced by {@code tryAcquireForTest()} (an {@code acquire()} that returns), followed by what
 * {@code tryEnter} does next, {@code afterTickerWait}. In package {@code dev.resilience4cata.generated}
 * for those seams, like {@link TimeoutStress}.
 *
 * <p>The waiter publishes its wait, made for its own thread, and the deadline actor spins until it sees
 * it, so the deadline interrupts the waiter's thread; the waiter reads and clears its interrupt flag at
 * the end (a set flag is an interrupt that leaked to the caller).
 */
public final class BulkheadTickerWaitStress {

    private BulkheadTickerWaitStress() {}

    static BulkheadPolicy bulkhead(int maxConcurrent) {
        return new BulkheadPolicy("stress",
                BulkheadConfig.builder().maxConcurrent(maxConcurrent).maxWait(Duration.ofHours(1)).build());
    }

    /** Waiter side: publish the wait, "acquire returns" if a permit is free, finish the wait. */
    static void waitFor(BulkheadPolicy bulkhead, Holder holder, III_Result r) {
        BulkheadPolicy.TickerWait wait = bulkhead.tickerWaitForTest(Thread.currentThread());
        holder.wait = wait;
        boolean acquired = bulkhead.tryAcquireForTest();
        r.r1 = bulkhead.afterTickerWait(wait, null, acquired) ? 1 : 0;
        r.r2 = Thread.interrupted() ? 1 : 0;
    }

    /** Deadline side: fires as soon as the wait exists. */
    static void deadline(Holder holder) {
        BulkheadPolicy.TickerWait wait;
        while ((wait = holder.wait) == null) {
            Thread.onSpinWait();
        }
        wait.run();
    }

    static class Holder {
        volatile BulkheadPolicy.@Nullable TickerWait wait;
    }

    /**
     * One free slot: the waiter's {@code acquire()} and the deadline's {@code tryAcquire()} compete for
     * it. Whoever wins, the call enters exactly once, holding exactly one permit, with no interrupt left.
     */
    @JCStressTest
    @Outcome(id = "1, 0, 1", expect = ACCEPTABLE, desc = "Entered with one permit, by acquire() or granted by the deadline.")
    @Outcome(id = ".*, 1, .*", expect = FORBIDDEN, desc = "The deadline's interrupt leaked to the caller.")
    @Outcome(expect = FORBIDDEN, desc = "Rejected with a free slot, or a permit lost or leaked.")
    @State
    public static class DeadlineRacesAcquire extends Holder {
        final BulkheadPolicy bulkhead = bulkhead(1);

        @Actor
        public void waiter(III_Result r) {
            waitFor(bulkhead, this, r);
        }

        @Actor
        public void deadline() {
            BulkheadTickerWaitStress.deadline(this);
        }

        @Arbiter
        public void permits(III_Result r) {
            r.r3 = bulkhead.concurrentCalls();
        }
    }

    /**
     * Two free slots: both the waiter's {@code acquire()} and the deadline may take one. The extra one
     * must be released: one call, one permit.
     */
    @JCStressTest
    @Outcome(id = "1, 0, 1", expect = ACCEPTABLE, desc = "Entered with one permit.")
    @Outcome(id = "1, 0, 2", expect = FORBIDDEN, desc = "The deadline's permit and acquire()'s were both kept.")
    @Outcome(id = ".*, 1, .*", expect = FORBIDDEN, desc = "The deadline's interrupt leaked to the caller.")
    @Outcome(expect = FORBIDDEN, desc = "Unexpected.")
    @State
    public static class GrantRacesAcquire extends Holder {
        final BulkheadPolicy bulkhead = bulkhead(2);

        @Actor
        public void waiter(III_Result r) {
            waitFor(bulkhead, this, r);
        }

        @Actor
        public void deadline() {
            BulkheadTickerWaitStress.deadline(this);
        }

        @Arbiter
        public void permits(III_Result r) {
            r.r3 = bulkhead.concurrentCalls();
        }
    }

    /**
     * A full bulkhead with an asynchronous waiter queued: the holder's {@code exit()} hands the slot to
     * the asynchronous waiter while the synchronous wait's deadline tries to take it. The slot goes to
     * exactly one of them. The synchronous waiter's thread is a never-started thread, so the deadline's
     * interrupt reaches nobody; the arbiter reads the deadline's decision.
     *
     * <p>Results: deadline decision (3 granted, 4 timed out), asynchronous waiter served (1), permits held.
     */
    @JCStressTest
    @Outcome(id = "3, 0, 1", expect = ACCEPTABLE, desc = "The deadline took the released slot; the async waiter keeps waiting.")
    @Outcome(id = "4, 1, 1", expect = ACCEPTABLE, desc = "The async waiter got the slot; the deadline gave up.")
    @Outcome(id = "3, 1, .*", expect = FORBIDDEN, desc = "One slot given to both.")
    @Outcome(expect = FORBIDDEN, desc = "A slot lost or leaked.")
    @State
    public static class GrantRacesHandOff {
        final BulkheadPolicy bulkhead = bulkhead(1);
        final BulkheadWaiters.Waiter<String> queued;
        final BulkheadPolicy.TickerWait wait;

        public GrantRacesHandOff() {
            bulkhead.tryAcquireForTest(); // the holder
            queued = bulkhead.enqueueForTest(CompletableFuture::new); // its call never completes: it keeps the slot
            wait = bulkhead.tickerWaitForTest(Thread.ofPlatform().unstarted(() -> {}));
        }

        @Actor
        public void holderExits() {
            bulkhead.exit();
        }

        @Actor
        public void deadline() {
            wait.run();
        }

        @Arbiter
        public void outcome(III_Result r) {
            r.r1 = wait.finish();
            r.r2 = queued.isClaimed() ? 1 : 0;
            r.r3 = bulkhead.concurrentCalls();
        }
    }
}
