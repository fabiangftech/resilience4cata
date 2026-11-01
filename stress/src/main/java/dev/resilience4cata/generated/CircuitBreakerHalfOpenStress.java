package dev.resilience4cata.generated;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.FORBIDDEN;

import dev.resilience4cata.CircuitBreakerConfig;
import java.time.Duration;
import java.util.List;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.II_Result;
import org.openjdk.jcstress.infra.results.III_Result;

/**
 * {@link CircuitBreakerPolicy} outside the closed count window: half-open probe permits and their
 * completion, and the time window's one-second buckets. A fixed clock ({@link PolicyEnvironment#forTest})
 * makes only the races decide. States in results are {@link dev.resilience4cata.CircuitBreakerState}
 * ordinals (0 closed, 1 open, 2 half-open); a generation is read as in {@code CircuitBreakerStress}: the
 * token {@code acquire()} returns after one more transition ({@code reset()}).
 */
public final class CircuitBreakerHalfOpenStress {

    private static final long SECOND = 1_000_000_000L;

    /** Preallocated: the outcome under test is the breaker's, not the allocation's. */
    static final Exception FAILURE = new Exception("stress");
    static final IllegalStateException IGNORED = new IllegalStateException("stress");

    private CircuitBreakerHalfOpenStress() {}

    static CircuitBreakerPolicy breaker(CircuitBreakerConfig.Builder config, long[] clock) {
        return new CircuitBreakerPolicy("stress", config.build(),
                PolicyEnvironment.forTest(() -> clock[0], nanos -> { }));
    }

    /** One failure opens it; no wait in the open state, so the next {@code acquire()} probes. */
    static CircuitBreakerConfig.Builder opensAtOnce(int halfOpenCalls) {
        return CircuitBreakerConfig.builder().windowSize(1).minimumCalls(1).failureRateThreshold(100)
                .waitInOpen(Duration.ZERO).halfOpenCalls(halfOpenCalls).ignoreOn(List.of(IllegalStateException.class));
    }

    static void fail(CircuitBreakerPolicy breaker) {
        long token = breaker.acquire();
        if (token >= 0) {
            breaker.onFailure(token, breaker.startTimer(), FAILURE);
        }
    }

    static int generationAfterReset(CircuitBreakerPolicy breaker) {
        breaker.reset();
        return (int) breaker.acquire();
    }

    /**
     * The breaker is open and its wait is over; two calls arrive with one probe permit. Both may race to
     * move it to half open, then for the permit: exactly one is let through. Results: the two callers
     * (1 admitted), and whether the arbiter's {@code acquire()} is rejected (1).
     */
    @JCStressTest
    @Outcome(id = {"1, 0, 1", "0, 1, 1"}, expect = ACCEPTABLE, desc = "Exactly one probe.")
    @Outcome(id = "1, 1, .*", expect = FORBIDDEN, desc = "Two probes for one permit.")
    @Outcome(expect = FORBIDDEN, desc = "A permit was lost or invented.")
    @State
    public static class OnePermitTwoProbes {
        final CircuitBreakerPolicy breaker = breaker(opensAtOnce(1), new long[] {0});

        public OnePermitTwoProbes() {
            fail(breaker);
        }

        @Actor
        public void probe1(III_Result r) {
            r.r1 = breaker.acquire() >= 0 ? 1 : 0;
        }

        @Actor
        public void probe2(III_Result r) {
            r.r2 = breaker.acquire() >= 0 ? 1 : 0;
        }

        @Arbiter
        public void after(III_Result r) {
            r.r3 = breaker.acquire() < 0 ? 1 : 0;
        }
    }

    /**
     * Two probes, taken in the setup, complete at the same time: one success, one failure (50 %, below
     * the 100 % threshold). Whichever records last sees both completed and closes the breaker, exactly
     * once; the breaker never stays half open with every probe done. Results: state, generation after a
     * reset (0 closed, 1 open, 2 half-open, 3 closed again, 4 after the reset).
     */
    @JCStressTest
    @Outcome(id = "0, 4", expect = ACCEPTABLE, desc = "Closed once after both probes.")
    @Outcome(id = "2, .*", expect = FORBIDDEN, desc = "Stuck half open: no thread saw both probes completed.")
    @Outcome(expect = FORBIDDEN, desc = "Unexpected.")
    @State
    public static class ProbesCompleteTogether {
        final CircuitBreakerPolicy breaker = breaker(opensAtOnce(2), new long[] {0});
        final long first;
        final long second;

        public ProbesCompleteTogether() {
            fail(breaker);
            first = breaker.acquire();
            second = breaker.acquire();
            if (first < 0 || second < 0) {
                throw new IllegalStateException("two probes expected");
            }
        }

        @Actor
        public void success() {
            breaker.onSuccess(first, 0L);
        }

        @Actor
        public void failure() {
            breaker.onFailure(second, 0L, FAILURE);
        }

        @Arbiter
        public void after(II_Result r) {
            r.r1 = breaker.state().ordinal();
            r.r2 = generationAfterReset(breaker);
        }
    }

    /**
     * The only probe permit is taken (setup); the probe's failure is ignored, which gives the permit
     * back, while another call asks for one. A rejected call must not use up the permit the ignored probe
     * gives back, so exactly one of that call and the arbiter's later {@code acquire()} gets it.
     * Results: the racing call (1 admitted), the arbiter (1 admitted).
     */
    @JCStressTest
    @Outcome(id = {"1, 0", "0, 1"}, expect = ACCEPTABLE, desc = "The given-back permit was used once.")
    @Outcome(id = "0, 0", expect = FORBIDDEN, desc = "A rejected call used up the given-back permit.")
    @Outcome(id = "1, 1", expect = FORBIDDEN, desc = "One permit used twice.")
    @Outcome(expect = FORBIDDEN, desc = "Unexpected.")
    @State
    public static class IgnoredProbeGivesPermitBack {
        final CircuitBreakerPolicy breaker = breaker(opensAtOnce(1), new long[] {0});
        final long probe;

        public IgnoredProbeGivesPermitBack() {
            fail(breaker);
            probe = breaker.acquire();
            if (probe < 0) {
                throw new IllegalStateException("a probe expected");
            }
        }

        @Actor
        public void ignoredProbe() {
            breaker.onFailure(probe, 0L, IGNORED);
        }

        @Actor
        public void caller(II_Result r) {
            r.r1 = breaker.acquire() >= 0 ? 1 : 0;
        }

        @Arbiter
        public void after(II_Result r) {
            r.r2 = breaker.acquire() >= 0 ? 1 : 0;
        }
    }

    /**
     * Time window of one second, two failures in a new second (100 %, at least two calls): both threads
     * find the previous second's bucket and race to replace it. The loser must record into the winner's
     * bucket, so the breaker opens, exactly once. Results: state, generation after a reset.
     */
    @JCStressTest
    @Outcome(id = "1, 2", expect = ACCEPTABLE, desc = "Opened once.")
    @Outcome(id = "0, .*", expect = FORBIDDEN, desc = "A failure was lost with a replaced bucket.")
    @Outcome(id = "1, 3", expect = FORBIDDEN, desc = "Opened twice.")
    @Outcome(expect = FORBIDDEN, desc = "Unexpected.")
    @State
    public static class TimeWindowNewSecond {
        final long[] clock = {0};
        final CircuitBreakerPolicy breaker = breaker(CircuitBreakerConfig.builder()
                .windowType(CircuitBreakerConfig.WindowType.TIME).windowDuration(Duration.ofSeconds(1))
                .minimumCalls(2).failureRateThreshold(100), clock);

        public TimeWindowNewSecond() {
            breaker.onSuccess(breaker.acquire(), 0L); // a bucket for second 0
            clock[0] = SECOND; // published to the actors by the jcstress state handoff
        }

        @Actor
        public void failure1() {
            fail(breaker);
        }

        @Actor
        public void failure2() {
            fail(breaker);
        }

        @Arbiter
        public void after(II_Result r) {
            r.r1 = breaker.state().ordinal();
            r.r2 = generationAfterReset(breaker);
        }
    }
}
