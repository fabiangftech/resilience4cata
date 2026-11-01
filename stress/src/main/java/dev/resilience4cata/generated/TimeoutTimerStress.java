package dev.resilience4cata.generated;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.FORBIDDEN;

import java.util.concurrent.atomic.AtomicInteger;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.III_Result;

/**
 * {@link TimeoutTimer} and {@link TimerEntry}: one entry's cancel racing the timer thread's steps (drain
 * of the inbox, expiry of the lane). Each test has its own manual timer ({@code new TimeoutTimer(launcher)},
 * moved only by an actor's {@code drainNew}/{@code expire}), so no background thread is involved. In
 * package {@code dev.resilience4cata.generated} for those seams, like {@link TimeoutStress}.
 *
 * <p>The invariant: exactly one of firing and cancelling wins ({@code cancel()} returns {@code true}
 * iff the entry never fires), and once the timer has ticked again it holds nothing ({@code pending() == 0}).
 * Results: {@code cancel()} (1 true), times fired, entries the timer still holds after one more tick.
 */
public final class TimeoutTimerStress {

    private static final long DURATION = 1_000_000L;

    private TimeoutTimerStress() {}

    static final class Entry extends TimerEntry {
        @Override
        public void run() {
            // never called: the launcher only counts
        }
    }

    static class Holder {
        final AtomicInteger fired = new AtomicInteger();
        final TimeoutTimer timer = new TimeoutTimer(task -> fired.incrementAndGet());
        final TimeoutTimer.Lane lane = timer.lane(DURATION);
        final Entry entry = new Entry();

        void outcome(III_Result r) {
            r.r2 = fired.get(); // before the next tick, which would fire an entry the actors' steps missed
            timer.tick(3 * DURATION); // unlinks what a cancel left on the cancelled stack
            r.r3 = timer.pending();
        }
    }

    /**
     * The entry is still in its inbox ({@code NEW}): the cancel races the timer draining it and then
     * expiring it. The cancel wins while the entry is {@code NEW} or {@code SCHEDULED}, else it fired.
     */
    @JCStressTest
    @Outcome(id = "1, 0, 0", expect = ACCEPTABLE, desc = "Cancelled before it fired.")
    @Outcome(id = "0, 1, 0", expect = ACCEPTABLE, desc = "Fired before the cancel.")
    @Outcome(id = "1, 1, .*", expect = FORBIDDEN, desc = "Cancelled and fired.")
    @Outcome(id = "0, 0, .*", expect = FORBIDDEN, desc = "Neither cancelled nor fired.")
    @Outcome(expect = FORBIDDEN, desc = "The timer still holds the entry.")
    @State
    public static class CancelVsDrainAndExpire extends Holder {
        public CancelVsDrainAndExpire() {
            timer.schedule(entry, lane);
        }

        @Actor
        public void cancel(III_Result r) {
            r.r1 = entry.cancel() ? 1 : 0;
        }

        @Actor
        public void timerThread() {
            timer.drainNew(0);
            timer.expire(DURATION);
        }

        @Arbiter
        public void after(III_Result r) {
            outcome(r);
        }
    }

    /**
     * The entry is in its lane ({@code SCHEDULED}): the cancel pushes it onto a cancelled stack (reusing
     * its inbox link) while the timer unlinks and fires it. Exactly one wins, and the cancelled stack
     * leaves nothing behind.
     */
    @JCStressTest
    @Outcome(id = "1, 0, 0", expect = ACCEPTABLE, desc = "Cancelled before it fired.")
    @Outcome(id = "0, 1, 0", expect = ACCEPTABLE, desc = "Fired before the cancel.")
    @Outcome(id = "1, 1, .*", expect = FORBIDDEN, desc = "Cancelled and fired.")
    @Outcome(id = "0, 0, .*", expect = FORBIDDEN, desc = "Neither cancelled nor fired.")
    @Outcome(expect = FORBIDDEN, desc = "The timer still holds the entry.")
    @State
    public static class CancelScheduledVsExpire extends Holder {
        public CancelScheduledVsExpire() {
            timer.schedule(entry, lane);
            timer.drainNew(0);
        }

        @Actor
        public void cancel(III_Result r) {
            r.r1 = entry.cancel() ? 1 : 0;
        }

        @Actor
        public void timerThread() {
            timer.drainCancelled();
            timer.expire(DURATION);
        }

        @Arbiter
        public void after(III_Result r) {
            outcome(r);
        }
    }

    /**
     * Two entries of one lane, one cancelled while the timer drains and expires both: the other one must
     * still fire exactly once (unlinking the cancelled neighbour must not cut it out of the lane).
     * Results: {@code cancel()} of the first, fires, entries left.
     */
    @JCStressTest
    @Outcome(id = "1, 1, 0", expect = ACCEPTABLE, desc = "The first was cancelled, the second fired.")
    @Outcome(id = "0, 2, 0", expect = ACCEPTABLE, desc = "Both fired before the cancel.")
    @Outcome(expect = FORBIDDEN, desc = "An entry was lost, fired twice, or left behind.")
    @State
    public static class CancelOneOfTwo extends Holder {
        final Entry second = new Entry();

        public CancelOneOfTwo() {
            timer.schedule(entry, lane);
            timer.schedule(second, lane);
        }

        @Actor
        public void cancel(III_Result r) {
            r.r1 = entry.cancel() ? 1 : 0;
        }

        @Actor
        public void timerThread() {
            timer.drainNew(0);
            timer.drainCancelled();
            timer.expire(DURATION);
        }

        @Arbiter
        public void after(III_Result r) {
            outcome(r);
        }
    }
}
