package dev.resilience4cata.generated;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.FORBIDDEN;

import dev.resilience4cata.CircuitBreakerConfig;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.IIII_Result;

/**
 * The quiet fast path of {@link CircuitBreakerPolicy}, checked from the inside: the arbiter reads the
 * private {@code quiet} field (through a private lookup, allowed because {@code core} is on the class
 * path in the same unnamed module) and recounts the count window ({@code closedWindowCounts()}, package
 * private). The invariant: the breaker is quiet only if its closed window holds only fast successes.
 *
 * <p>A failure whose outcome is hidden by a stale quiet state is not visible through the public API
 * until much later (the next successes are skipped, so the failure never ages out of the window), which
 * is why this test looks at the field.
 */
public final class CircuitBreakerQuietStress {

    static final Exception FAILURE = new Exception("stress");
    static final VarHandle QUIET;

    static {
        try {
            QUIET = MethodHandles.privateLookupIn(CircuitBreakerPolicy.class, MethodHandles.lookup())
                    .findVarHandle(CircuitBreakerPolicy.class, "quiet", long.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private CircuitBreakerQuietStress() {}

    /**
     * A window of two holds one success. A success (which would fill the window with fast successes and
     * publish the quiet state) races with a failure (counted before it clears the quiet state). At the
     * threshold of 100% one failure out of two does not open the breaker, so it stays closed with exactly
     * one failure in its window, and must not be quiet.
     *
     * <p>Results: quiet ({@code 1} if the quiet field holds a token), failures in the window by its
     * counter, failures by recounting the slots, state ordinal ({@code 0} closed).
     */
    @JCStressTest
    @Outcome(id = "0, 1, 1, 0", expect = ACCEPTABLE, desc = "Closed, one failure counted, not quiet.")
    @Outcome(id = "1, .*", expect = FORBIDDEN, desc = "Quiet with a failure in the window: later successes would be skipped.")
    @Outcome(expect = FORBIDDEN, desc = "Counters drifted or unexpected state.")
    @State
    public static class FailureRacesQuiet {
        final CircuitBreakerPolicy breaker = new CircuitBreakerPolicy("stress", CircuitBreakerConfig.builder()
                .windowSize(2).minimumCalls(2).failureRateThreshold(100f).build());

        public FailureRacesQuiet() {
            breaker.onSuccess(breaker.acquire(), breaker.startTimer());
        }

        @Actor
        public void success() {
            long token = breaker.acquire();
            breaker.onSuccess(token, breaker.startTimer());
        }

        @Actor
        public void failure() {
            long token = breaker.acquire();
            breaker.onFailure(token, breaker.startTimer(), FAILURE);
        }

        @Arbiter
        public void after(IIII_Result r) {
            r.r1 = (long) QUIET.getVolatile(breaker) >= 0 ? 1 : 0;
            int[] counts = breaker.closedWindowCounts();
            r.r2 = counts[1];
            r.r3 = counts[4];
            r.r4 = breaker.state().ordinal();
        }
    }
}
