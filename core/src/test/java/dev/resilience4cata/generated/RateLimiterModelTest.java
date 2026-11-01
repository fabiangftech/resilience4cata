package dev.resilience4cata.generated;

import dev.resilience4cata.RateLimiterConfig;
import dev.resilience4cata.RateLimiterSnapshot;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Drives {@link RateLimiterPolicy} through random sequences of synchronous calls (that pass, wait, are
 * interrupted while waiting, or are rejected), asynchronous reservations and clock moves, and compares it
 * after every step with a token bucket written from the documentation (docs/en/policies/rate-limiter.md):
 * permits are evenly spaced every {@code period / permits} (rounded down to the nanosecond), unused ones are
 * saved up to {@code permits}, and a call may wait for its permit up to {@code maxWait}. The model counts
 * credit in nanoseconds instead of a theoretical arrival time. On top of it, every admitted call is checked
 * against the documented bound: a stretch of time {@code L} sees at most {@code permits + L / interval}
 * calls start. The clock sometimes wraps around {@code Long.MAX_VALUE}, as {@code nanoTime} may.
 */
class RateLimiterModelTest {

    private static final long MILLI = 1_000_000L;
    private static final int SEEDS = 2000;
    private static final int STEPS = 400;

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @Test
    void matchesTheModel() {
        ModelRuns.forEachSeed(SEEDS, RateLimiterModelTest::run);
    }

    private static void run(long seed) {
        SplittableRandom random = new SplittableRandom(seed);
        int permits = random.nextInt(4) == 0 ? 1 : random.nextInt(1, 21);
        long period = switch (random.nextInt(3)) {
            case 0 -> random.nextLong(1, 2_000) * MILLI; // often not a multiple of permits
            case 1 -> random.nextLong(1, 2_000) * MILLI + random.nextLong(1, 1_000);
            default -> random.nextLong(permits, 50L * permits); // a few nanoseconds per permit
        };
        long maxWait = random.nextBoolean() ? 0 : random.nextLong(1, 3 * period + 2);
        RateLimiterConfig config = RateLimiterConfig.builder().enabled(random.nextInt(20) != 0).permits(permits)
                .period(Duration.ofNanos(period)).maxWait(Duration.ofNanos(maxWait)).build();
        long start = switch (random.nextInt(4)) {
            case 0 -> 0L;
            case 1 -> -random.nextLong(1L << 50);
            case 2 -> Long.MAX_VALUE - random.nextLong(3 * period + 1); // wraps during the run
            default -> random.nextLong(1L << 50);
        };
        ModelRuns.Clock clock = new ModelRuns.Clock(start);
        ModelRuns.Trace trace = new ModelRuns.Trace(seed, config + ", start " + start);
        long[] interruptAfter = {-1}; // >= 0: the next sleep is interrupted after that many nanoseconds
        List<Long> sleeps = new ArrayList<>();
        RateLimiterPolicy limiter = new RateLimiterPolicy("model", config, clock::read, nanos -> {
            sleeps.add(nanos);
            if (interruptAfter[0] >= 0) {
                clock.now += Math.min(interruptAfter[0], nanos);
                interruptAfter[0] = -1;
                throw new InterruptedException();
            }
            clock.now += nanos; // a sleep moves the clock, as on a ManualClock
        });
        Model model = new Model(config);
        List<Long> starts = new ArrayList<>(); // when each admitted call starts (or is due, if reserved)

        compare(trace, model, limiter, clock.now);
        for (int step = 0; step < STEPS; step++) {
            int op = random.nextInt(100);
            if (op < 45) {
                boolean interrupted = maxWait > 0 && random.nextInt(6) == 0;
                long after = interrupted ? random.nextLong(model.interval + 1) : -1;
                interruptAfter[0] = after;
                sleeps.clear();
                long before = clock.now;
                boolean passed = limiter.tryAcquire();
                boolean flag = Thread.interrupted();
                trace.step("tryAcquire at " + before + (interrupted ? " (interrupted " + after + " ns into a sleep)" : "")
                        + " -> " + passed + ", slept " + sleeps + (flag ? ", interrupt flag set" : ""));
                Model.Decision expected = model.acquire();
                if (expected.delay > 0) {
                    trace.equal("sleeps", List.of(expected.delay), sleeps);
                    if (interrupted) {
                        model.advance(Math.min(after, expected.delay));
                        model.giveBack();
                        trace.equal("passed", false, passed);
                        trace.equal("interrupt flag", true, flag);
                    } else {
                        model.advance(expected.delay);
                        trace.equal("passed", true, passed);
                        trace.equal("interrupt flag", false, flag);
                    }
                } else {
                    trace.equal("sleeps", List.of(), sleeps);
                    trace.equal("passed", expected.passes, passed);
                    trace.equal("interrupt flag", false, flag);
                }
                interruptAfter[0] = -1;
                if (passed) {
                    starts.add(clock.now);
                }
            } else if (op < 60) {
                long wait = limiter.reserve();
                trace.step("reserve at " + clock.now + " -> " + wait);
                Model.Decision expected = model.acquire();
                trace.equal("reserve", expected.passes ? expected.delay : -1L, wait);
                if (wait >= 0) {
                    starts.add(clock.now + wait);
                }
            } else {
                long by = advance(random, model);
                clock.now += by;
                model.advance(by);
                trace.step("advance " + by + " ns to " + clock.now);
            }
            compare(trace, model, limiter, clock.now);
            if (model.enabled) {
                checkBound(trace, starts, permits, model.interval);
            }
        }
    }

    /** The documented bound: in any stretch {@code L} of time, at most {@code permits + L / interval} calls start. */
    private static void checkBound(ModelRuns.Trace trace, List<Long> starts, int permits, long interval) {
        int last = starts.size() - 1;
        if (last < 0) {
            return;
        }
        long latest = starts.get(last);
        // A longer stretch only allows more: looking a few bursts back is enough to find a violation.
        for (int first = last; first >= Math.max(0, last - 4 * permits - 4); first--) {
            long span = latest - starts.get(first);
            trace.check(span >= 0, "calls started out of order: " + starts.get(first) + " after " + latest);
            long allowed = permits + span / interval;
            trace.check(last - first + 1 <= allowed, (last - first + 1) + " calls started within " + span
                    + " ns, at most " + allowed + " allowed");
        }
    }

    private static long advance(SplittableRandom random, Model model) {
        return switch (random.nextInt(8)) {
            case 0 -> 0L;
            case 1 -> random.nextLong(1, 4); // a few nanoseconds
            case 2 -> model.interval + random.nextInt(3) - 1; // around one permit
            case 3 -> Math.max(0, model.enabled ? model.toNextPermit() + random.nextInt(3) - 1 : 0);
            case 4 -> random.nextLong(1, model.interval + 1);
            case 5 -> random.nextLong(1, 3 * model.tolerance + 1);
            default -> random.nextLong(1, model.interval * 3 + 1);
        };
    }

    private static void compare(ModelRuns.Trace trace, Model model, RateLimiterPolicy limiter, long now) {
        trace.equal("availablePermits", model.available(), limiter.availablePermits());
        trace.equal("snapshot", new RateLimiterSnapshot("model", model.available(), model.permits), limiter.snapshot());
    }

    /**
     * A token bucket that keeps its content as credit in nanoseconds: one permit costs {@code interval}, the
     * bucket holds at most {@code permits × interval}, and time adds credit one nanosecond per nanosecond. A
     * call that finds less than one permit's credit borrows it if the debt is paid off within {@code maxWait},
     * and then waits for exactly that time.
     */
    private static final class Model {
        final boolean enabled;
        final int permits;
        final long interval;
        final long tolerance;
        final long maxWait;
        long credit;

        Model(RateLimiterConfig config) {
            enabled = config.enabled();
            permits = config.permits();
            interval = config.period().toNanos() / permits;
            tolerance = interval * permits;
            maxWait = config.maxWait().toNanos();
            credit = tolerance; // a full burst from the start
        }

        record Decision(boolean passes, long delay) {
        }

        Decision acquire() {
            if (!enabled) {
                return new Decision(true, 0);
            }
            long after = credit - interval;
            long wait = Math.max(0, -after);
            if (wait > maxWait) {
                return new Decision(false, 0);
            }
            credit = after;
            return new Decision(true, wait);
        }

        void giveBack() {
            credit = Math.min(tolerance, credit + interval);
        }

        void advance(long nanos) {
            credit = Math.min(tolerance, credit + nanos);
        }

        int available() {
            if (!enabled) {
                return permits;
            }
            return credit <= 0 ? 0 : (int) (credit / interval);
        }

        /** Nanoseconds until one more permit is available without waiting. */
        long toNextPermit() {
            long needed = ((credit <= 0 ? 0 : credit / interval) + 1) * interval;
            return Math.min(needed, tolerance) - credit;
        }
    }
}
