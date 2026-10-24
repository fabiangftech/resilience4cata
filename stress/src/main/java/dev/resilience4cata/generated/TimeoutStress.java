package dev.resilience4cata.generated;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.FORBIDDEN;

import dev.resilience4cata.CallTimeoutException;
import dev.resilience4cata.TimeoutConfig;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.III_Result;
import org.openjdk.jcstress.infra.results.II_Result;

/**
 * {@link TimeoutPolicy} and {@link Watchdog}: the stamped scope protocol between a timed call and the
 * watchdog that interrupts it. Each test uses its own manual watchdog ({@code new Watchdog(0)}, moved
 * only by {@code tick(nanos)} from an actor), so no background thread is involved.
 *
 * <p>This class is in {@code dev.resilience4cata.generated} to reach those package-private test seams;
 * that works because the stress module is not modular ({@code core} is on the class path, so both halves
 * of the package share the unnamed module and class loader). The calls themselves use only the
 * protocol generated code uses: {@code enter()}, then {@code succeeded(scope)} or {@code failed(scope, e)}.
 *
 * <p>Every actor that runs a timed call reads and clears its interrupt flag afterwards: a set flag
 * there is an interrupt that leaked past the call (and clearing it keeps the jcstress thread clean).
 */
public final class TimeoutStress {

    /** Far past any call's start + timeout + tick. */
    static final long LATE = 1_000_000_000L;
    static final Exception FAILURE = new Exception("stress");

    private TimeoutStress() {}

    static TimeoutPolicy policy(Watchdog watchdog) {
        return new TimeoutPolicy("stress", new TimeoutConfig(true, Duration.ofMillis(1)), watchdog);
    }

    /** Ends a call normally: {@code 1} if it timed out, {@code 0} if not. */
    static int succeed(TimeoutPolicy policy, TimeoutPolicy.@Nullable Scope scope) {
        try {
            policy.succeeded(scope);
            return 0;
        } catch (CallTimeoutException e) {
            return 1;
        }
    }

    static int leaked() {
        return Thread.interrupted() ? 1 : 0;
    }

    /**
     * A watchdog tick that finds the call overdue races with the call returning. Either the call ends
     * first (no timeout) or the watchdog wins and the call throws {@code CallTimeoutException}; in both
     * cases no interrupt is left on the thread once the call has returned or thrown.
     */
    @JCStressTest
    @Outcome(id = "0, 0", expect = ACCEPTABLE, desc = "The call ended before the watchdog.")
    @Outcome(id = "1, 0", expect = ACCEPTABLE, desc = "The watchdog won: timed out, interrupt cleared.")
    @Outcome(id = ".*, 1", expect = FORBIDDEN, desc = "The watchdog's interrupt leaked past the call.")
    @Outcome(expect = FORBIDDEN, desc = "Unexpected.")
    @State
    public static class TickRacesSuccess {
        final Watchdog watchdog = new Watchdog(0);
        final TimeoutPolicy policy = policy(watchdog);

        @Actor
        public void call(II_Result r) {
            TimeoutPolicy.Scope scope = policy.enter();
            r.r1 = succeed(policy, scope);
            r.r2 = leaked();
        }

        @Actor
        public void tick() {
            watchdog.tick(LATE);
        }
    }

    /** Same race on the failure path: {@code failed} rethrows nothing, or a timeout with the failure as cause. */
    @JCStressTest
    @Outcome(id = "0, 0", expect = ACCEPTABLE, desc = "The call ended before the watchdog.")
    @Outcome(id = "1, 0", expect = ACCEPTABLE, desc = "The watchdog won: timed out with the failure as cause.")
    @Outcome(id = ".*, 1", expect = FORBIDDEN, desc = "The watchdog's interrupt leaked past the call.")
    @Outcome(expect = FORBIDDEN, desc = "Unexpected (2: timeout without the failure as its cause).")
    @State
    public static class TickRacesFailure {
        final Watchdog watchdog = new Watchdog(0);
        final TimeoutPolicy policy = policy(watchdog);

        @Actor
        public void call(II_Result r) {
            TimeoutPolicy.Scope scope = policy.enter();
            try {
                policy.failed(scope, FAILURE);
                r.r1 = 0;
            } catch (CallTimeoutException e) {
                r.r1 = e.getCause() == FAILURE ? 1 : 2;
            }
            r.r2 = leaked();
        }

        @Actor
        public void tick() {
            watchdog.tick(LATE);
        }
    }

    /**
     * ABA on a reused scope. The caller makes two timed calls in a row on the same thread, so the second
     * reuses the first one's scope. The watchdog actor decides with the stamp of the <em>first</em> call
     * (as a watchdog that read the stamp, then got delayed, would): it may time out the first call, but
     * never the second, and no interrupt is left after the caller is done.
     *
     * <p>Results: first call timed out (plus 2 if an interrupt leaked past it), second call timed out,
     * interrupt leaked after the second call.
     */
    @JCStressTest
    @Outcome(id = "0, 0, 0", expect = ACCEPTABLE, desc = "The stale stamp no longer matched: nothing happened.")
    @Outcome(id = "1, 0, 0", expect = ACCEPTABLE, desc = "The first call was still running and timed out.")
    @Outcome(id = ".*, 1, .*", expect = FORBIDDEN, desc = "ABA: a stamp of the first call interrupted the second.")
    @Outcome(id = ".*, 1", expect = FORBIDDEN, desc = "An interrupt leaked past the calls.")
    @Outcome(expect = FORBIDDEN, desc = "Unexpected.")
    @State
    public static class StaleStampNeverInterruptsNextCall {
        final Watchdog watchdog = new Watchdog(0);
        final TimeoutPolicy policy = policy(watchdog);
        volatile long firstStamp;
        volatile TimeoutPolicy.@Nullable Scope firstScope;

        @Actor
        public void caller(III_Result r) {
            TimeoutPolicy.Scope first = policy.enter();
            if (first != null) {
                firstStamp = first.stampForTest();
                firstScope = first; // published after the stamp
            }
            r.r1 = succeed(policy, first) + 2 * leaked(); // 2 or 3: an interrupt leaked past the first call
            TimeoutPolicy.Scope second = policy.enter();
            r.r2 = succeed(policy, second);
            r.r3 = leaked();
        }

        @Actor
        public void staleWatchdog() {
            TimeoutPolicy.Scope scope = firstScope;
            if (scope != null) {
                scope.expireIfOverdue(LATE, firstStamp);
            }
        }
    }

    /**
     * A reused scope whose next call has a shorter timeout. The caller ends call n of a policy with a
     * long timeout (1 s), then starts call n + 1 of a policy with a short one (1 ms), on the same thread,
     * so the same scope. The watchdog actor decides with the stamp of call n and a clock (100 ms) past
     * call n + 1's limit but not call n's: if it could pair call n's stamp with call n + 1's timeout, it
     * would interrupt call n early. Call n must never time out, and call n + 1 is never interrupted by a
     * stamp of call n.
     *
     * <p>Results: call n timed out (plus 2 if an interrupt leaked past it), call n + 1 timed out,
     * interrupt leaked after call n + 1.
     */
    @JCStressTest
    @Outcome(id = "0, 0, 0", expect = ACCEPTABLE, desc = "Nothing was interrupted.")
    @Outcome(id = "1, .*", expect = FORBIDDEN, desc = "Call n was interrupted before its own timeout.")
    @Outcome(id = ".*, 1, .*", expect = FORBIDDEN, desc = "ABA: a stamp of call n interrupted call n + 1.")
    @Outcome(expect = FORBIDDEN, desc = "An interrupt leaked, or unexpected.")
    @State
    public static class ShorterNextTimeoutNeverEarly {
        static final long BETWEEN = 100_000_000L;
        final Watchdog watchdog = new Watchdog(0);
        final TimeoutPolicy slow = new TimeoutPolicy("slow", new TimeoutConfig(true, Duration.ofSeconds(1)), watchdog);
        final TimeoutPolicy fast = policy(watchdog);
        volatile long firstStamp;
        volatile TimeoutPolicy.@Nullable Scope firstScope;

        @Actor
        public void caller(III_Result r) {
            TimeoutPolicy.Scope first = slow.enter();
            if (first != null) {
                firstStamp = first.stampForTest();
                firstScope = first;
            }
            r.r1 = succeed(slow, first) + 2 * leaked();
            TimeoutPolicy.Scope second = fast.enter();
            r.r2 = succeed(fast, second);
            r.r3 = leaked();
        }

        @Actor
        public void staleWatchdog() {
            TimeoutPolicy.Scope scope = firstScope;
            if (scope != null) {
                scope.expireIfOverdue(BETWEEN, firstStamp);
            }
        }
    }
}
