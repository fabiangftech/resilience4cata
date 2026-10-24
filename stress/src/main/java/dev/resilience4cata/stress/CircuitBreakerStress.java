package dev.resilience4cata.stress;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.FORBIDDEN;

import dev.resilience4cata.CircuitBreakerConfig;
import dev.resilience4cata.generated.CircuitBreakerPolicy;
import java.time.Duration;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.III_Result;
import org.openjdk.jcstress.infra.results.II_Result;
import org.openjdk.jcstress.infra.results.I_Result;

/**
 * {@link CircuitBreakerPolicy} with a count window, public API only. States in results are
 * {@link dev.resilience4cata.CircuitBreakerState} ordinals: 0 closed, 1 open, 2 half-open, 3 forced
 * open, 4 disabled. The breaker's generation is observed by calling {@code reset()} (one more
 * transition) and then {@code acquire()}, which returns the closed phase's generation as its token.
 */
public final class CircuitBreakerStress {

    /** Preallocated: the outcome under test is the breaker's, not the allocation's. */
    static final Exception FAILURE = new Exception("stress");

    private CircuitBreakerStress() {}

    static CircuitBreakerPolicy breaker(CircuitBreakerConfig.Builder config) {
        return new CircuitBreakerPolicy("stress", config.build());
    }

    static void fail(CircuitBreakerPolicy breaker) {
        long token = breaker.acquire();
        if (token >= 0) {
            breaker.onFailure(token, breaker.startTimer(), FAILURE);
        }
    }

    static int succeed(CircuitBreakerPolicy breaker) {
        long token = breaker.acquire();
        if (token >= 0) {
            breaker.onSuccess(token, breaker.startTimer());
        }
        return (int) token;
    }

    /** Generation of the phase after one more transition (consumes the breaker: only for arbiters). */
    static int generationAfterReset(CircuitBreakerPolicy breaker) {
        breaker.reset();
        return (int) breaker.acquire();
    }

    /**
     * Two failures fill a window of two (minimum two calls, 100%, so that a failure lost by the counters
     * keeps the breaker closed): the breaker must open, whichever thread evaluates the window last, and
     * reject afterwards. Results: state, the arbiter's
     * {@code acquire()}, the generation after a reset (0 closed, 1 open, 2 after the reset; 3 would mean
     * a second transition).
     */
    @JCStressTest
    @Outcome(id = "1, -1, 2", expect = ACCEPTABLE, desc = "Opened exactly once and rejects.")
    @Outcome(id = "0, .*", expect = FORBIDDEN, desc = "Both failures counted but the breaker stayed closed.")
    @Outcome(id = "1, -1, 3", expect = FORBIDDEN, desc = "Opened twice.")
    @Outcome(expect = FORBIDDEN, desc = "Unexpected.")
    @State
    public static class ConcurrentFailuresOpenOnce {
        final CircuitBreakerPolicy breaker = breaker(CircuitBreakerConfig.builder()
                .windowSize(2).minimumCalls(2).failureRateThreshold(100f));

        @Actor
        public void failure1() {
            fail(breaker);
        }

        @Actor
        public void failure2() {
            fail(breaker);
        }

        @Arbiter
        public void after(III_Result r) {
            r.r1 = breaker.state().ordinal();
            r.r2 = (int) breaker.acquire();
            r.r3 = generationAfterReset(breaker);
        }
    }

    /**
     * A token taken while closed (generation 0) is reported as a failure while the breaker, already
     * open, lets a probe through (half-open, generation 2) and the probe succeeds. The stale failure
     * must be dropped: it can neither decide the probe nor reopen the breaker, so the breaker ends closed
     * (generation 3; 4 after the reset).
     */
    @JCStressTest
    @Outcome(id = "0, 4", expect = ACCEPTABLE, desc = "The probe closed the breaker; the stale failure was dropped.")
    @Outcome(expect = FORBIDDEN, desc = "The stale failure changed the outcome.")
    @State
    public static class StaleOutcomeCannotDecideProbe {
        final CircuitBreakerPolicy breaker = breaker(CircuitBreakerConfig.builder()
                .windowSize(1).minimumCalls(1).failureRateThreshold(100f)
                .waitInOpen(Duration.ZERO).halfOpenCalls(1));
        final long staleToken;

        public StaleOutcomeCannotDecideProbe() {
            staleToken = breaker.acquire();
            fail(breaker); // opens: generation 1
        }

        @Actor
        public void probe() {
            succeed(breaker); // open -> half-open (2) -> closed (3)
        }

        @Actor
        public void staleFailure() {
            breaker.onFailure(staleToken, 0L, FAILURE);
        }

        @Arbiter
        public void after(II_Result r) {
            r.r1 = breaker.state().ordinal();
            r.r2 = generationAfterReset(breaker);
        }
    }

    /**
     * A call that acquired while closed fails while the breaker is forced open. If the failure is
     * recorded first, it opens the breaker (generation 1) and forcing it open is a second transition;
     * otherwise the failure is stale and dropped, or loses the compare-and-set against the forced open
     * phase. Either way the breaker ends forced open, never merely open.
     */
    @JCStressTest
    @Outcome(id = "3, 2", expect = ACCEPTABLE, desc = "Forced open first; the failure was dropped.")
    @Outcome(id = "3, 3", expect = ACCEPTABLE, desc = "The failure opened the breaker, then it was forced open.")
    @Outcome(id = "1, .*", expect = FORBIDDEN, desc = "The failure's transition overwrote the forced open state.")
    @Outcome(expect = FORBIDDEN, desc = "Unexpected.")
    @State
    public static class FailureRacesForceOpen {
        final CircuitBreakerPolicy breaker = breaker(CircuitBreakerConfig.builder()
                .windowSize(1).minimumCalls(1).failureRateThreshold(100f));
        final long token = breaker.acquire();

        @Actor
        public void forceOpen() {
            breaker.forceOpen();
        }

        @Actor
        public void failure() {
            breaker.onFailure(token, 0L, FAILURE);
        }

        @Arbiter
        public void after(II_Result r) {
            r.r1 = breaker.state().ordinal();
            r.r2 = generationAfterReset(breaker);
        }
    }

    /**
     * The window already holds only fast successes, so the breaker is quiet: {@code acquire()} and
     * {@code onSuccess} only read the quiet field. Forcing it open must clear that state, so the
     * arbiter's call is rejected however the success interleaves.
     */
    @JCStressTest
    @Outcome(id = "-1", expect = ACCEPTABLE, desc = "Rejected after the forced open.")
    @Outcome(expect = FORBIDDEN, desc = "A forced open breaker let a call through.")
    @State
    public static class ForceOpenEndsQuietState {
        final CircuitBreakerPolicy breaker = breaker(CircuitBreakerConfig.builder()
                .windowSize(2).minimumCalls(2));

        public ForceOpenEndsQuietState() {
            succeed(breaker);
            succeed(breaker); // window full of fast successes: quiet
        }

        @Actor
        public void forceOpen() {
            breaker.forceOpen();
        }

        @Actor
        public void success() {
            succeed(breaker);
        }

        @Arbiter
        public void after(I_Result r) {
            r.r1 = (int) breaker.acquire();
        }
    }

    /**
     * The success that fills the window makes the breaker quiet, racing with a forced open: a success
     * that read the closed phase before the transition must not publish the quiet state after the
     * transition cleared it (it re-checks the phase and undoes it). The arbiter's call must be rejected.
     */
    @JCStressTest
    @Outcome(id = "-1", expect = ACCEPTABLE, desc = "Rejected after the forced open.")
    @Outcome(expect = FORBIDDEN, desc = "A late quiet state let a call through a forced open breaker.")
    @State
    public static class LateQuietAfterForceOpen {
        final CircuitBreakerPolicy breaker = breaker(CircuitBreakerConfig.builder()
                .windowSize(2).minimumCalls(2));

        public LateQuietAfterForceOpen() {
            succeed(breaker); // one slot left: the next success makes the breaker quiet
        }

        @Actor
        public void forceOpen() {
            breaker.forceOpen();
        }

        @Actor
        public void success() {
            succeed(breaker);
        }

        @Arbiter
        public void after(I_Result r) {
            r.r1 = (int) breaker.acquire();
        }
    }
}
