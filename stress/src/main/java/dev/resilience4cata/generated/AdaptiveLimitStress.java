package dev.resilience4cata.generated;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.FORBIDDEN;

import dev.resilience4cata.AdaptiveLimitConfig;
import dev.resilience4cata.LimitExceededException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.III_Result;
import org.openjdk.jcstress.infra.results.II_Result;
import org.openjdk.jcstress.infra.results.JJ_Result;

/**
 * {@link AdaptiveLimitPolicy}: the slot counter against the current limit, and the window that exactly one
 * completing call ends. A fixed clock (a test environment) puts every completion after the first window's
 * deadline. In results, {@code 1} means {@code acquire()} gave a slot; the last value is read by the arbiter
 * after every actor finished.
 */
public final class AdaptiveLimitStress {

    private AdaptiveLimitStress() {}

    private static final long WINDOW = Duration.ofMillis(10).toNanos();

    /** A limit starting at {@code initial}, on a clock the state sets; every completed call is enough to end a window. */
    static AdaptiveLimitPolicy policy(AtomicLong clock, int initial, AdaptiveLimitConfig.Algorithm algorithm) {
        AdaptiveLimitConfig config = AdaptiveLimitConfig.builder().algorithm(algorithm).initialLimit(initial)
                .minLimit(1).maxLimit(100).window(Duration.ofNanos(WINDOW)).minWindowSamples(1).build();
        return new AdaptiveLimitPolicy("stress", config, PolicyEnvironment.forTest(clock::get, nanos -> { }), 2);
    }

    static int acquire(AdaptiveLimitPolicy policy) {
        try {
            policy.acquire();
            return 1;
        } catch (LimitExceededException e) {
            return 0;
        }
    }

    /** Two callers race for the only slot and keep it: exactly one gets in. */
    @JCStressTest
    @Outcome(id = {"1, 0, 1", "0, 1, 1"}, expect = ACCEPTABLE, desc = "Exactly one caller took the slot.")
    @Outcome(id = "1, 1, .*", expect = FORBIDDEN, desc = "Both got in: the limit was exceeded.")
    @Outcome(expect = FORBIDDEN, desc = "Lost or invented slot.")
    @State
    public static class OneSlotTwoCallers {
        final AdaptiveLimitPolicy policy = policy(new AtomicLong(), 1, AdaptiveLimitConfig.Algorithm.GRADIENT2);

        @Actor
        public void caller1(III_Result r) {
            r.r1 = acquire(policy);
        }

        @Actor
        public void caller2(III_Result r) {
            r.r2 = acquire(policy);
        }

        @Arbiter
        public void count(III_Result r) {
            r.r3 = policy.inFlight();
        }
    }

    /**
     * Two complete calls (acquire, then success) on two slots: both get in, and both releases are counted, so
     * the limit ends empty. A lost release would leak a slot forever.
     */
    @JCStressTest
    @Outcome(id = "1, 1, 0", expect = ACCEPTABLE, desc = "Both got in and both slots were given back.")
    @Outcome(id = "1, 1, 1", expect = FORBIDDEN, desc = "A concurrent release was lost: one slot leaked.")
    @Outcome(expect = FORBIDDEN, desc = "Unexpected.")
    @State
    public static class ConcurrentReleasesAllCounted {
        final AdaptiveLimitPolicy policy = policy(new AtomicLong(), 2, AdaptiveLimitConfig.Algorithm.GRADIENT2);

        int call() {
            long start;
            try {
                start = policy.acquire();
            } catch (LimitExceededException e) {
                return 0;
            }
            policy.onSuccess(start);
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
            r.r3 = policy.inFlight();
        }
    }

    /**
     * Two calls complete after the window's deadline, each enough to end it: exactly one of them ends the
     * window (the other finds the lock taken, or the deadline moved), so the algorithm runs once. Its AIMD limit
     * goes from 2 to 3 (both calls were in flight together, or one alone: at least half of 2), never to 4.
     */
    @JCStressTest
    @Outcome(id = "1, 3", expect = ACCEPTABLE, desc = "One window ended, the limit grew once.")
    @Outcome(id = "2, .*", expect = FORBIDDEN, desc = "The window ended twice.")
    @Outcome(expect = FORBIDDEN, desc = "No window ended, or the limit moved wrongly.")
    @State
    public static class WindowEndsOnce {
        final AtomicLong clock = new AtomicLong();
        final AdaptiveLimitPolicy policy = policy(clock, 2, AdaptiveLimitConfig.Algorithm.AIMD);
        final long first;
        final long second;

        public WindowEndsOnce() {
            first = policy.acquire();
            second = policy.acquire();
            clock.set(2 * WINDOW); // both complete after the first deadline (WINDOW), before the second (3 WINDOW)
        }

        @Actor
        public void caller1() {
            policy.onSuccess(first);
        }

        @Actor
        public void caller2() {
            policy.onSuccess(second);
        }

        @Arbiter
        public void count(JJ_Result r) {
            r.r1 = policy.windowsForTest();
            r.r2 = policy.limit();
        }
    }

    /**
     * The only slot is held. One actor frees it while another tries to take it: the rejection of a full limit
     * does not change the counter, so the counter ends at 1 if and only if the second actor got in.
     */
    @JCStressTest
    @Outcome(id = {"1, 1", "0, 0"}, expect = ACCEPTABLE, desc = "Took the freed slot, or was rejected before it was freed.")
    @Outcome(expect = FORBIDDEN, desc = "The rejection or the release changed the counter wrongly.")
    @State
    public static class RejectionLeavesCounter {
        final AdaptiveLimitPolicy policy = policy(new AtomicLong(), 1, AdaptiveLimitConfig.Algorithm.GRADIENT2);
        final long held = policy.acquire();

        @Actor
        public void release() {
            policy.onSuccess(held);
        }

        @Actor
        public void caller(II_Result r) {
            r.r1 = acquire(policy);
        }

        @Arbiter
        public void count(II_Result r) {
            r.r2 = policy.inFlight();
        }
    }
}
