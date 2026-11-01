package dev.resilience4cata.generated;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.FORBIDDEN;

import dev.resilience4cata.BulkheadConfig;
import dev.resilience4cata.BulkheadFullException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.III_Result;
import org.openjdk.jcstress.infra.results.II_Result;

/**
 * {@link BulkheadPolicy} with {@code maxWait > 0}: the asynchronous waiters of {@link BulkheadWaiters}
 * racing the release that hands a slot over. The holder's slot is taken in the setup; a granted waiter's
 * call never completes, so it keeps the slot it was given (the grant runs it on a new virtual thread).
 * In package {@code dev.resilience4cata.generated} for the {@code *ForTest} seams, like
 * {@link CancellationStress}.
 */
public final class BulkheadWaitersStress {

    private BulkheadWaitersStress() {}

    static BulkheadPolicy bulkhead() {
        return new BulkheadPolicy("stress",
                BulkheadConfig.builder().maxConcurrent(1).maxWait(Duration.ofHours(1)).build());
    }

    /** 1 if a permit is free (and leaves it taken: arbiters only). */
    static int freePermit(BulkheadPolicy bulkhead) {
        return bulkhead.tryAcquireForTest() ? 1 : 0;
    }

    /**
     * A caller queues (counts itself, offers itself, then drains, as {@code executeAsync} does) while the
     * holder exits (releases, then reads the count). One side sees the other: a slot is never left free
     * while a waiter stays queued (no lost wake-up). Results: waiter served (1), free permit (1), waiters
     * still counted.
     */
    @JCStressTest
    @Outcome(id = "1, 0, 0", expect = ACCEPTABLE, desc = "The released slot went to the waiter.")
    @Outcome(id = "0, 1, 1", expect = FORBIDDEN, desc = "Lost wake-up: a free slot and a waiter still queued.")
    @Outcome(id = "1, 1, .*", expect = FORBIDDEN, desc = "The waiter was served and the slot is free too.")
    @Outcome(expect = FORBIDDEN, desc = "Unexpected.")
    @State
    public static class EnqueueVsExit {
        final BulkheadPolicy bulkhead = bulkhead();
        volatile BulkheadWaiters.Waiter<String> waiter;

        public EnqueueVsExit() {
            bulkhead.tryAcquireForTest(); // the holder
        }

        @Actor
        public void enqueue() {
            waiter = bulkhead.enqueueForTest(CompletableFuture::new);
            bulkhead.drainForTest();
        }

        @Actor
        public void holderExits() {
            bulkhead.exit();
        }

        @Arbiter
        public void after(III_Result r) {
            r.r1 = waiter.isClaimed() ? 1 : 0;
            r.r3 = bulkhead.queuedCalls();
            r.r2 = freePermit(bulkhead);
        }
    }

    /**
     * A queued waiter's {@code maxWait} ends ({@code run()}, its timer task) while the holder exits and
     * hands the slot to it. Exactly one claims the waiter: either it fails with the rejection and the
     * slot ends free, or it got the slot and does not fail. Results: waiter failed with
     * {@link BulkheadFullException} (1), free permit (1), waiters still counted.
     */
    @JCStressTest
    @Outcome(id = "1, 1, 0", expect = ACCEPTABLE, desc = "Timed out; the slot went back to the bulkhead.")
    @Outcome(id = "0, 0, 0", expect = ACCEPTABLE, desc = "Served before its timeout.")
    @Outcome(id = "1, 0, .*", expect = FORBIDDEN, desc = "Timed out, yet the slot was handed to it (leaked).")
    @Outcome(id = "0, 1, .*", expect = FORBIDDEN, desc = "Neither served nor timed out, with a free slot.")
    @Outcome(expect = FORBIDDEN, desc = "Unexpected.")
    @State
    public static class TimeoutVsHandOff {
        final BulkheadPolicy bulkhead = bulkhead();
        final BulkheadWaiters.Waiter<String> waiter;

        public TimeoutVsHandOff() {
            bulkhead.tryAcquireForTest(); // the holder
            waiter = bulkhead.enqueueForTest(CompletableFuture::new);
        }

        @Actor
        public void holderExits() {
            bulkhead.exit();
        }

        @Actor
        public void timeout() {
            waiter.run();
        }

        @Arbiter
        public void after(III_Result r) {
            r.r1 = waiter.result.isCompletedExceptionally()
                    && waiter.result.exceptionNow() instanceof BulkheadFullException ? 1 : 0;
            r.r3 = bulkhead.queuedCalls();
            r.r2 = freePermit(bulkhead);
        }
    }

    /**
     * Two waiters are queued and the holder exits while the older one times out: the slot must go to
     * the younger one rather than be lost. Results: older failed (1), younger served (1).
     */
    @JCStressTest
    @Outcome(id = {"1, 1", "0, 0"}, expect = ACCEPTABLE,
            desc = "The older timed out and the younger got the slot, or the older got it first.")
    @Outcome(id = "1, 0", expect = FORBIDDEN, desc = "The older timed out and the slot went to nobody.")
    @Outcome(expect = FORBIDDEN, desc = "Unexpected.")
    @State
    public static class TimeoutOfOlderVsHandOff {
        final BulkheadPolicy bulkhead = bulkhead();
        final BulkheadWaiters.Waiter<String> older;
        final BulkheadWaiters.Waiter<String> younger;

        public TimeoutOfOlderVsHandOff() {
            bulkhead.tryAcquireForTest(); // the holder
            older = bulkhead.enqueueForTest(CompletableFuture::new);
            younger = bulkhead.enqueueForTest(CompletableFuture::new);
        }

        @Actor
        public void holderExits() {
            bulkhead.exit();
        }

        @Actor
        public void olderTimesOut() {
            older.run();
        }

        @Arbiter
        public void after(II_Result r) {
            boolean olderFailed = older.result.isCompletedExceptionally();
            r.r1 = olderFailed ? 1 : 0;
            r.r2 = younger.isClaimed() ? 1 : 0;
        }
    }
}
