package dev.resilience4cata.generated;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.FORBIDDEN;

import dev.resilience4cata.RateLimiterConfig;
import java.time.Duration;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.III_Result;
import org.openjdk.jcstress.infra.results.IIII_Result;

/**
 * {@link RateLimiterPolicy} with a clock that never moves, so that only the races decide the outcome.
 *
 * <p>This class is in {@code dev.resilience4cata.generated} to reach the package-private constructor
 * that takes a ticker and a sleeper. That works because the stress module is not modular: {@code core}
 * is on the class path, so both halves of the package are in the same unnamed module and class loader.
 */
public final class RateLimiterStress {

    private static final long SECOND = 1_000_000_000L;

    private RateLimiterStress() {}

    /**
     * Three callers race for a burst of two permits: exactly two get in, and the arbiter finds no
     * permit left ({@code reserve()} with {@code maxWait = 0} rejects: {@code 1} in the last value).
     */
    @JCStressTest
    @Outcome(id = {"1, 1, 0, 1", "1, 0, 1, 1", "0, 1, 1, 1"}, expect = ACCEPTABLE, desc = "Exactly the burst got in.")
    @Outcome(id = "1, 1, 1, .*", expect = FORBIDDEN, desc = "More than the burst got in.")
    @Outcome(expect = FORBIDDEN, desc = "A permit was lost or invented.")
    @State
    public static class BurstNeverExceeded {
        final RateLimiterPolicy limiter = new RateLimiterPolicy("stress",
                RateLimiterConfig.builder().permits(2).period(Duration.ofSeconds(1)).build(), () -> 0L, nanos -> {});

        int acquire() {
            return limiter.tryAcquire() ? 1 : 0;
        }

        @Actor
        public void caller1(IIII_Result r) {
            r.r1 = acquire();
        }

        @Actor
        public void caller2(IIII_Result r) {
            r.r2 = acquire();
        }

        @Actor
        public void caller3(IIII_Result r) {
            r.r3 = acquire();
        }

        @Arbiter
        public void left(IIII_Result r) {
            r.r4 = limiter.reserve() < 0 ? 1 : 0;
        }
    }

    /**
     * One permit per second, {@code maxWait = 1s}, and the burst is used up when the test starts, so
     * every caller must reserve the next permit and wait for it. The first caller's wait is interrupted
     * (its sleeper throws), so it must give its reservation back; the second caller's wait completes.
     *
     * <p>Results: first caller {@code 2} = rejected after an interrupted wait with the interrupt flag
     * set again, {@code 0} = rejected without waiting (the second caller had reserved first); second
     * caller {@code 1} = admitted, {@code 0} = rejected; last value = how many seconds ahead the
     * permits are booked, probed by the arbiter with {@code reserve()} ({@code 1} if the next permit is
     * free within {@code maxWait}, {@code 2} if one more is still reserved).
     */
    @JCStressTest
    @Outcome(id = {"2, 1, 2", "0, 1, 2"}, expect = ACCEPTABLE, desc = "The second caller holds the only reservation.")
    @Outcome(id = "2, 0, 1", expect = ACCEPTABLE,
            desc = "The second caller was rejected while the first held a reservation, then the first gave it back.")
    @Outcome(id = "2, 0, 2", expect = FORBIDDEN, desc = "The interrupted wait did not give its permit back.")
    @Outcome(expect = FORBIDDEN, desc = "Unexpected.")
    @State
    public static class InterruptedWaitGivesPermitBack {
        volatile Thread interrupted;
        final RateLimiterPolicy limiter = new RateLimiterPolicy("stress",
                RateLimiterConfig.builder().permits(1).period(Duration.ofSeconds(1)).maxWait(Duration.ofSeconds(1)).build(), () -> 0L, nanos -> {
                    if (Thread.currentThread() == interrupted) {
                        throw new InterruptedException();
                    }
                });

        public InterruptedWaitGivesPermitBack() {
            if (!limiter.tryAcquire()) {
                throw new IllegalStateException("the burst must be available");
            }
        }

        @Actor
        public void interruptedCaller(III_Result r) {
            interrupted = Thread.currentThread();
            boolean admitted = limiter.tryAcquire();
            boolean flag = Thread.interrupted(); // also clears it for jcstress's next use of this thread
            r.r1 = admitted ? 1 : flag ? 2 : 0;
        }

        @Actor
        public void patientCaller(III_Result r) {
            r.r2 = limiter.tryAcquire() ? 1 : 0;
        }

        @Arbiter
        public void booked(III_Result r) {
            long wait = limiter.reserve();
            r.r3 = wait < 0 ? 2 : (int) ((wait + SECOND - 1) / SECOND);
        }
    }

    /**
     * Like {@link InterruptedWaitGivesPermitBack}, but {@code maxWait = 2s}, so the second caller can
     * always reserve, even behind the first caller's reservation. The first caller's give-back may then
     * race with the second caller's compare-and-set: both updates must land, so exactly one permit is
     * booked ahead in the end (the arbiter's {@code reserve()} waits 2 s: {@code 2}; {@code 1} would mean
     * a reservation was lost, {@code 3} a permit not given back).
     */
    @JCStressTest
    @Outcome(id = "2, 1, 2", expect = ACCEPTABLE, desc = "Both updates landed: one permit booked ahead.")
    @Outcome(id = "2, 1, 1", expect = FORBIDDEN, desc = "The give-back overwrote the concurrent reservation.")
    @Outcome(id = "2, 1, 3", expect = FORBIDDEN, desc = "The interrupted wait did not give its permit back.")
    @Outcome(expect = FORBIDDEN, desc = "Unexpected.")
    @State
    public static class GiveBackRacesReservation {
        volatile Thread interrupted;
        final RateLimiterPolicy limiter = new RateLimiterPolicy("stress",
                RateLimiterConfig.builder().permits(1).period(Duration.ofSeconds(1)).maxWait(Duration.ofSeconds(2)).build(), () -> 0L, nanos -> {
                    if (Thread.currentThread() == interrupted) {
                        throw new InterruptedException();
                    }
                });

        public GiveBackRacesReservation() {
            if (!limiter.tryAcquire()) {
                throw new IllegalStateException("the burst must be available");
            }
        }

        @Actor
        public void interruptedCaller(III_Result r) {
            interrupted = Thread.currentThread();
            boolean admitted = limiter.tryAcquire();
            boolean flag = Thread.interrupted();
            r.r1 = admitted ? 1 : flag ? 2 : 0;
        }

        @Actor
        public void patientCaller(III_Result r) {
            r.r2 = limiter.tryAcquire() ? 1 : 0;
        }

        @Arbiter
        public void booked(III_Result r) {
            long wait = limiter.reserve();
            r.r3 = wait < 0 ? 3 : (int) ((wait + SECOND - 1) / SECOND);
        }
    }
}
