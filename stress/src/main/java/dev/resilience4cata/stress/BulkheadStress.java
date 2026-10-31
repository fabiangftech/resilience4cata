package dev.resilience4cata.stress;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.FORBIDDEN;

import dev.resilience4cata.BulkheadConfig;
import dev.resilience4cata.generated.BulkheadPolicy;
import java.time.Duration;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.III_Result;
import org.openjdk.jcstress.infra.results.IIII_Result;
import org.openjdk.jcstress.infra.results.II_Result;

/**
 * {@link BulkheadPolicy} without waiting ({@code maxWait = 0}): the compare-and-set loop on the slot
 * counter. Public API only. In results, {@code 1} means {@code tryEnter()} returned {@code true}; the
 * last value is {@code concurrentCalls()} read by the arbiter after every actor finished.
 */
public final class BulkheadStress {

    private BulkheadStress() {}

    static BulkheadPolicy bulkhead(int maxConcurrent) {
        return new BulkheadPolicy("stress", BulkheadConfig.builder().maxConcurrent(maxConcurrent).build());
    }

    static int enter(BulkheadPolicy bulkhead) {
        return bulkhead.tryEnter() ? 1 : 0;
    }

    /** Two callers race for the only slot and keep it: exactly one gets in. */
    @JCStressTest
    @Outcome(id = {"1, 0, 1", "0, 1, 1"}, expect = ACCEPTABLE, desc = "Exactly one caller took the slot.")
    @Outcome(id = "1, 1, .*", expect = FORBIDDEN, desc = "Both got in: the limit was exceeded.")
    @Outcome(expect = FORBIDDEN, desc = "Lost or invented slot.")
    @State
    public static class OneSlotTwoCallers {
        final BulkheadPolicy bulkhead = bulkhead(1);

        @Actor
        public void caller1(III_Result r) {
            r.r1 = enter(bulkhead);
        }

        @Actor
        public void caller2(III_Result r) {
            r.r2 = enter(bulkhead);
        }

        @Arbiter
        public void count(III_Result r) {
            r.r3 = bulkhead.concurrentCalls();
        }
    }

    /** Three callers race for two slots and keep them: exactly two get in. */
    @JCStressTest
    @Outcome(id = {"1, 1, 0, 2", "1, 0, 1, 2", "0, 1, 1, 2"}, expect = ACCEPTABLE, desc = "Exactly two callers got in.")
    @Outcome(id = "1, 1, 1, .*", expect = FORBIDDEN, desc = "All three got in: the limit was exceeded.")
    @Outcome(expect = FORBIDDEN, desc = "Lost or invented slot.")
    @State
    public static class TwoSlotsThreeCallers {
        final BulkheadPolicy bulkhead = bulkhead(2);

        @Actor
        public void caller1(IIII_Result r) {
            r.r1 = enter(bulkhead);
        }

        @Actor
        public void caller2(IIII_Result r) {
            r.r2 = enter(bulkhead);
        }

        @Actor
        public void caller3(IIII_Result r) {
            r.r3 = enter(bulkhead);
        }

        @Arbiter
        public void count(IIII_Result r) {
            r.r4 = bulkhead.concurrentCalls();
        }
    }

    /**
     * Two complete calls (enter, then exit if admitted) on one slot: at least one gets in, and every
     * admitted call gives its slot back, so the bulkhead ends empty.
     */
    @JCStressTest
    @Outcome(id = {"1, 1, 0", "1, 0, 0", "0, 1, 0"}, expect = ACCEPTABLE, desc = "Slot taken and given back.")
    @Outcome(id = "0, 0, .*", expect = FORBIDDEN, desc = "Both rejected although the slot was free for one of them.")
    @Outcome(expect = FORBIDDEN, desc = "A slot was lost or leaked.")
    @State
    public static class EnterExitNeverLosesSlot {
        final BulkheadPolicy bulkhead = bulkhead(1);

        int call() {
            if (!bulkhead.tryEnter()) {
                return 0;
            }
            bulkhead.exit();
            return 1;
        }

        @Actor
        public void caller1(III_Result r) {
            r.r1 = call();
        }

        @Actor
        public void caller2(III_Result r) {
            r.r2 = call();
        }

        @Arbiter
        public void count(III_Result r) {
            r.r3 = bulkhead.concurrentCalls();
        }
    }

    /**
     * Two complete calls on two slots: both always get in, and the concurrent exits must both be
     * counted, so the bulkhead ends empty (a lost decrement would leak a slot forever).
     */
    @JCStressTest
    @Outcome(id = "1, 1, 0", expect = ACCEPTABLE, desc = "Both got in and both slots were given back.")
    @Outcome(id = "1, 1, 1", expect = FORBIDDEN, desc = "A concurrent exit was lost: one slot leaked.")
    @Outcome(expect = FORBIDDEN, desc = "Unexpected.")
    @State
    public static class ConcurrentExitsAllCounted {
        final BulkheadPolicy bulkhead = bulkhead(2);

        int call() {
            if (!bulkhead.tryEnter()) {
                return 0;
            }
            bulkhead.exit();
            return 1;
        }

        @Actor
        public void caller1(III_Result r) {
            r.r1 = call();
        }

        @Actor
        public void caller2(III_Result r) {
            r.r2 = call();
        }

        @Arbiter
        public void count(III_Result r) {
            r.r3 = bulkhead.concurrentCalls();
        }
    }

    /**
     * The only slot is held when the test starts. One actor gives it back while another tries to
     * enter: the rejection of a full bulkhead must not change the counter, so the counter ends at 1 if
     * and only if the second actor got in.
     */
    @JCStressTest
    @Outcome(id = "1, 1", expect = ACCEPTABLE, desc = "Entered after the exit: the slot is held again.")
    @Outcome(id = "0, 0", expect = ACCEPTABLE, desc = "Rejected before the exit: the bulkhead ends empty.")
    @Outcome(expect = FORBIDDEN, desc = "The rejection or the exit changed the counter wrongly.")
    @State
    public static class RejectionLeavesCounter {
        final BulkheadPolicy bulkhead = bulkhead(1);

        public RejectionLeavesCounter() {
            if (!bulkhead.tryEnter()) {
                throw new IllegalStateException("the first slot must be free");
            }
        }

        @Actor
        public void exit() {
            bulkhead.exit();
        }

        @Actor
        public void enter(II_Result r) {
            r.r1 = BulkheadStress.enter(bulkhead);
        }

        @Arbiter
        public void count(II_Result r) {
            r.r2 = bulkhead.concurrentCalls();
        }
    }

    /** A full bulkhead rejects two concurrent callers without touching the counter. */
    @JCStressTest
    @Outcome(id = "0, 0, 1", expect = ACCEPTABLE, desc = "Both rejected; the counter is unchanged.")
    @Outcome(expect = FORBIDDEN, desc = "A full bulkhead let a call in or changed the counter.")
    @State
    public static class FullBulkheadOnlyReads {
        final BulkheadPolicy bulkhead = bulkhead(1);

        public FullBulkheadOnlyReads() {
            if (!bulkhead.tryEnter()) {
                throw new IllegalStateException("the first slot must be free");
            }
        }

        @Actor
        public void caller1(III_Result r) {
            r.r1 = enter(bulkhead);
        }

        @Actor
        public void caller2(III_Result r) {
            r.r2 = enter(bulkhead);
        }

        @Arbiter
        public void count(III_Result r) {
            r.r3 = bulkhead.concurrentCalls();
        }
    }
}
