package dev.resilience4cata.generated;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE_INTERESTING;
import static org.openjdk.jcstress.annotations.Expect.FORBIDDEN;

import dev.resilience4cata.RetryConfig;
import java.time.Duration;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.III_Result;
import org.openjdk.jcstress.infra.results.IIII_Result;
import org.openjdk.jcstress.infra.results.JJ_Result;

/**
 * {@link RetryBudget} with fixed clock readings, so that only the races decide the outcome. In
 * {@code dev.resilience4cata.generated} for the package-private constructor (see {@link RateLimiterStress}).
 */
public final class RetryBudgetStress {

    private static final long SECOND = 1_000_000_000L;

    private RetryBudgetStress() {}

    /** A reserve of {@code reserve} retries over a 1 s window, no share of the calls, one stripe. */
    static RetryBudget budget(int reserve) {
        return new RetryBudget(RetryConfig.builder().budgetMinRetriesPerSecond(reserve)
                .budgetWindow(Duration.ofSeconds(1)).build(), 0L, 1);
    }

    /** Three failures race for a budget of two retries: exactly two are granted, and two are counted. */
    @JCStressTest
    @Outcome(id = {"1, 1, 0, 2", "1, 0, 1, 2", "0, 1, 1, 2"}, expect = ACCEPTABLE, desc = "Exactly the budget was granted.")
    @Outcome(expect = FORBIDDEN, desc = "A retry was granted beyond the budget, or one was lost.")
    @State
    public static class RetriesNeverExceedTheBudget {
        final RetryBudget budget = budget(2);

        @Actor
        public void failure1(IIII_Result r) {
            r.r1 = budget.tryAcquire(0) ? 1 : 0;
        }

        @Actor
        public void failure2(IIII_Result r) {
            r.r2 = budget.tryAcquire(0) ? 1 : 0;
        }

        @Actor
        public void failure3(IIII_Result r) {
            r.r3 = budget.tryAcquire(0) ? 1 : 0;
        }

        @Arbiter
        public void count(IIII_Result r) {
            r.r4 = (int) budget.snapshot(0).retries();
        }
    }

    /**
     * Two callers on the same stripe count two calls each. The increment is lossy on purpose (a plain
     * read-and-write is far cheaper than a compare-and-set on every successful call), so a race can lose
     * calls, which only lowers the budget; it must never count a call that did not happen.
     */
    @JCStressTest
    @Outcome(id = "4, 0", expect = ACCEPTABLE, desc = "Every call counted.")
    @Outcome(id = {"2, 0", "3, 0"}, expect = ACCEPTABLE_INTERESTING, desc = "A race on the shared stripe lost calls: fewer retries, never more.")
    @Outcome(expect = FORBIDDEN, desc = "More calls than were made, or a retry counted.")
    @State
    public static class CallsAreNeverOvercounted {
        final RetryBudget budget = budget(1);

        @Actor
        public void caller1() {
            budget.onCall();
            budget.onCall();
        }

        @Actor
        public void caller2() {
            budget.onCall();
            budget.onCall();
        }

        @Arbiter
        public void count(JJ_Result r) {
            r.r1 = budget.snapshot(0).calls();
            r.r2 = budget.snapshot(0).retries();
        }
    }

    /**
     * The window has moved past the one retry granted at time 0, and two failures race to move the marks
     * and take the refilled reserve of one: exactly one is granted, whichever thread writes the marks.
     */
    @JCStressTest
    @Outcome(id = {"1, 0, 1", "0, 1, 1"}, expect = ACCEPTABLE, desc = "The refilled reserve was granted once.")
    @Outcome(expect = FORBIDDEN, desc = "The reserve was granted twice, or not at all.")
    @State
    public static class MovingTheWindowGrantsTheReserveOnce {
        final RetryBudget budget = budget(1);

        public MovingTheWindowGrantsTheReserveOnce() {
            if (!budget.tryAcquire(0)) {
                throw new AssertionError("the first retry must be granted");
            }
        }

        @Actor
        public void failure1(III_Result r) {
            r.r1 = budget.tryAcquire(2 * SECOND) ? 1 : 0;
        }

        @Actor
        public void failure2(III_Result r) {
            r.r2 = budget.tryAcquire(2 * SECOND) ? 1 : 0;
        }

        @Arbiter
        public void count(III_Result r) {
            r.r3 = (int) budget.snapshot(2 * SECOND).retries();
        }
    }
}
