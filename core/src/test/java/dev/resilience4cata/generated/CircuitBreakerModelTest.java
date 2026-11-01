package dev.resilience4cata.generated;

import dev.resilience4cata.CircuitBreakerConfig;
import dev.resilience4cata.CircuitBreakerSnapshot;
import dev.resilience4cata.CircuitBreakerState;
import dev.resilience4cata.Classifier;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * Drives {@link CircuitBreakerPolicy} through long random sequences of calls (that overlap, complete late,
 * fail, are slow or ignored), clock moves and handle operations, and compares it after every step with a
 * plain reference model of the documented behaviour (docs/en/policies/circuit-breaker.md). See
 * {@link ModelRuns} to replay a seed.
 */
class CircuitBreakerModelTest {

    private static final long SECOND = 1_000_000_000L;
    private static final long MILLI = 1_000_000L;
    private static final int SEEDS = 1500;
    private static final int STEPS = 400;

    /** A few thresholds that are exact in binary and a few that are not. */
    private static final double[] THRESHOLDS = {1, 10, 12.3, 20, 25, 100.0 / 3, 40, 50, 60, 200.0 / 3, 75, 80, 90, 100};

    /** How often each situation came up over all seeds, so that a model that never gets there is noticed. */
    private static final TreeMap<String, Integer> SEEN = new TreeMap<>();

    @AfterAll
    static void everySituationCameUp() {
        if (System.getProperty("test.seed") != null) {
            return;
        }
        for (String situation : List.of("count opened", "time opened", "count opened by slow calls",
                "time opened by slow calls", "half-open closed", "half-open opened", "half-open timed out",
                "stale outcome dropped", "probe released", "time window success evaluated")) {
            Integer count = SEEN.get(situation);
            if (count == null || count < 50) {
                throw new AssertionError("situation '" + situation + "' came up " + count + " times: " + SEEN);
            }
        }
    }

    private static void seen(String situation) {
        SEEN.merge(situation, 1, Integer::sum);
    }

    @Test
    void countWindowsMatchTheModel() {
        ModelRuns.forEachSeed(SEEDS, seed -> run(seed, CircuitBreakerConfig.WindowType.COUNT));
    }

    @Test
    void timeWindowsMatchTheModel() {
        ModelRuns.forEachSeed(SEEDS, seed -> run(seed, CircuitBreakerConfig.WindowType.TIME));
    }

    private static void run(long seed, CircuitBreakerConfig.WindowType type) {
        SplittableRandom random = new SplittableRandom(seed * 31 + type.ordinal());
        CircuitBreakerConfig config = config(random, type);
        long start = switch (random.nextInt(5)) {
            case 0 -> 0L;
            case 4 -> Long.MAX_VALUE - random.nextLong(100L * SECOND); // wraps during the run
            case 1 -> -random.nextLong(1_000L * SECOND); // nanoTime may be negative
            case 2 -> random.nextLong(1_000L * SECOND);
            default -> 7 * SECOND - 1; // just before a second boundary
        };
        ModelRuns.Clock clock = new ModelRuns.Clock(start);
        CircuitBreakerPolicy breaker = new CircuitBreakerPolicy("model", config, clock::read);
        Model model = new Model(config, start);
        ModelRuns.Trace trace = new ModelRuns.Trace(seed, config + ", start " + start);
        List<long[]> pending = new ArrayList<>(); // {policy token, model token, start}

        compare(trace, model, breaker, clock.now);
        for (int step = 0; step < STEPS; step++) {
            int op = random.nextInt(100);
            if (op < 35 || (op < 70 && pending.isEmpty())) {
                long token = breaker.acquire();
                long expected = model.acquire(clock.now);
                trace.step("acquire -> " + token + " at " + clock.now);
                trace.equal("acquire token", expected, token);
                if (token >= 0) {
                    pending.add(new long[] {token, expected, breaker.startTimer()});
                }
            } else if (op < 70 || pending.size() > 6) {
                long[] call = pending.remove(random.nextInt(pending.size()));
                complete(random, trace, breaker, model, call, clock.now);
            } else if (op < 94) {
                long by = random.nextInt(3) == 0 ? toBoundary(random, model, pending, clock.now) : advance(random, model.timeWindow);
                clock.now += by;
                trace.step("advance " + Duration.ofNanos(by) + " to " + clock.now);
            } else if (op < 96) {
                trace.step("forceOpen");
                breaker.forceOpen();
                model.forceOpen();
            } else if (op < 98) {
                trace.step("disable");
                breaker.disable();
                model.disable();
            } else {
                trace.step("reset");
                breaker.reset();
                model.reset(clock.now);
            }
            compare(trace, model, breaker, clock.now);
        }
    }

    private static void complete(SplittableRandom random, ModelRuns.Trace trace, CircuitBreakerPolicy breaker,
            Model model, long[] call, long now) {
        long token = call[0];
        long started = call[2];
        boolean slow = model.timed && now - started >= model.slowNanos;
        int kind = random.nextInt(9);
        String name;
        Outcome outcome;
        switch (kind) {
            case 0, 1, 2 -> {
                name = "success";
                outcome = Outcome.SUCCESS;
                breaker.onSuccess(token, started);
            }
            case 3, 4 -> {
                name = "failure";
                outcome = Outcome.FAILURE;
                breaker.onFailure(token, started, new IOException("boom"));
            }
            case 5 -> {
                name = "ignored failure";
                outcome = Outcome.IGNORED;
                breaker.onFailure(token, started, new IllegalStateException("ignored"));
            }
            case 6 -> {
                name = "error (not recorded as a failure)";
                outcome = Outcome.SUCCESS;
                breaker.onFailure(token, started, new AssertionError("error"));
            }
            default -> {
                Classifier.Outcome classified = Classifier.Outcome.values()[random.nextInt(3)];
                boolean thrown = random.nextBoolean();
                name = "classified " + classified + (thrown ? " exception" : " result");
                outcome = switch (classified) {
                    case SUCCESS -> Outcome.SUCCESS;
                    case FAILURE -> Outcome.FAILURE;
                    case IGNORE -> Outcome.IGNORED;
                };
                if (thrown) {
                    breaker.onFailure(token, started, new IOException("classified"), classified);
                } else {
                    breaker.onResult(token, started, classified);
                }
            }
        }
        trace.step(name + (slow ? " (slow)" : "") + " of token " + token + " at " + now);
        model.complete(call[1], outcome, slow, now);
    }

    /**
     * Moves the clock to just before, at or just after an instant where the documented behaviour changes:
     * the end of {@code waitInOpen} or {@code maxWaitInHalfOpen}, a pending call becoming slow, or the next
     * second (time windows).
     */
    private static long toBoundary(SplittableRandom random, Model model, List<long[]> pending, long now) {
        long target = switch (random.nextInt(4)) {
            case 0 -> model.openedAt + model.waitInOpen;
            case 1 -> model.halfOpenSince + model.maxWaitInHalfOpen;
            case 2 -> pending.isEmpty() ? now : pending.get(random.nextInt(pending.size()))[2] + model.slowNanos;
            default -> model.windowOrigin + (Math.floorDiv(now - model.windowOrigin, SECOND) + 1) * SECOND;
        };
        long by = target + random.nextInt(3) - 1 - now; // by subtraction: the clock may wrap
        return by >= 0 && by <= 10 * SECOND ? by : 0; // an instant already passed (or long gone): stay
    }

    private static long advance(SplittableRandom random, boolean timeWindow) {
        if (timeWindow) { // enough calls per second to fill a window of a few seconds
            return switch (random.nextInt(10)) {
                case 0, 1 -> 0L;
                case 2, 3, 4, 5 -> random.nextLong(1, 20 * MILLI);
                case 6, 7, 8 -> random.nextLong(20 * MILLI, 300 * MILLI);
                default -> random.nextLong(300 * MILLI, 3 * SECOND);
            };
        }
        return switch (random.nextInt(10)) {
            case 0, 1 -> 0L;
            case 2, 3, 4 -> random.nextLong(1, 50 * MILLI);
            case 5, 6, 7 -> random.nextLong(50 * MILLI, 1_500 * MILLI);
            default -> random.nextLong(1_500 * MILLI, 8 * SECOND);
        };
    }

    private static CircuitBreakerConfig config(SplittableRandom random, CircuitBreakerConfig.WindowType type) {
        CircuitBreakerConfig.Builder builder = CircuitBreakerConfig.builder()
                .enabled(random.nextInt(20) != 0)
                .windowType(type)
                .windowSize(random.nextInt(1, 11))
                .windowDuration(Duration.ofSeconds(random.nextInt(1, 6)))
                .minimumCalls(random.nextInt(1, 13))
                .failureRateThreshold(THRESHOLDS[random.nextInt(THRESHOLDS.length)])
                .slowCallRateThreshold(THRESHOLDS[random.nextInt(THRESHOLDS.length)])
                .slowCallDuration(random.nextBoolean() ? Duration.ZERO : Duration.ofMillis(random.nextInt(1, 400)))
                .waitInOpen(Duration.ofMillis(random.nextInt(1, 4_000)))
                .halfOpenCalls(random.nextInt(1, 5))
                .maxWaitInHalfOpen(random.nextBoolean() ? Duration.ZERO : Duration.ofMillis(random.nextInt(1, 4_000)))
                .ignoreOn(List.of(IllegalStateException.class));
        return builder.build();
    }

    private static void compare(ModelRuns.Trace trace, Model model, CircuitBreakerPolicy breaker, long now) {
        trace.equal("state", model.state, breaker.state());
        trace.equal("snapshot", model.snapshot(now), breaker.snapshot());
        if (model.state == CircuitBreakerState.CLOSED && !model.timeWindow) {
            int[] counts = breaker.closedWindowCounts();
            trace.check(counts[0] == counts[3] && counts[1] == counts[4] && counts[2] == counts[5],
                    "count window counters drifted from its slots: " + java.util.Arrays.toString(counts));
        }
    }

    private enum Outcome { SUCCESS, FAILURE, IGNORED }

    /**
     * The reference model, written from the documentation: one state at a time with a generation that every
     * transition increments; an outcome is recorded only by the state (generation) its call started in.
     */
    private static final class Model {
        final boolean enabled;
        final boolean timeWindow;
        final int windowSize;
        final long windowSeconds;
        final int minimumCalls;
        final double failureThreshold;
        final double slowThreshold;
        final boolean timed;
        final long slowNanos;
        final long waitInOpen;
        final long maxWaitInHalfOpen;
        final int halfOpenCalls;

        CircuitBreakerState state;
        long generation;
        /** Closed, time window: when the window was created; its seconds count from there. */
        long windowOrigin;
        long openedAt;
        long halfOpenSince;
        int permits;
        /** Closed, count window: the last {@code windowSize} outcomes; half-open: every probe outcome. */
        final ArrayDeque<int[]> outcomes = new ArrayDeque<>();
        /** Closed, time window: {second, failure, slow} of every recorded call. */
        final List<long[]> timedOutcomes = new ArrayList<>();
        /** Closed, time window: the seconds that already have a recorded call. */
        final Set<Long> secondsWithCalls = new HashSet<>();

        Model(CircuitBreakerConfig config, long now) {
            enabled = config.enabled();
            timeWindow = config.windowType() == CircuitBreakerConfig.WindowType.TIME;
            windowSize = config.windowSize();
            windowSeconds = config.windowDuration().getSeconds();
            minimumCalls = timeWindow ? config.minimumCalls() : Math.min(config.minimumCalls(), config.windowSize());
            failureThreshold = config.failureRateThreshold();
            slowThreshold = config.slowCallRateThreshold();
            slowNanos = config.slowCallDuration().toNanos();
            timed = slowNanos > 0;
            waitInOpen = config.waitInOpen().toNanos();
            maxWaitInHalfOpen = config.maxWaitInHalfOpen().toNanos();
            halfOpenCalls = config.halfOpenCalls();
            enterInitial(0, now);
        }

        private void enterInitial(long nextGeneration, long now) {
            generation = nextGeneration;
            windowOrigin = now;
            state = enabled ? CircuitBreakerState.CLOSED : CircuitBreakerState.DISABLED;
            clearWindow();
        }

        private void clearWindow() {
            outcomes.clear();
            timedOutcomes.clear();
            secondsWithCalls.clear();
        }

        private void open(long now) {
            generation++;
            state = CircuitBreakerState.OPEN;
            openedAt = now;
            clearWindow();
        }

        long acquire(long now) {
            switch (state) {
                case CLOSED, DISABLED -> {
                    return generation;
                }
                case FORCED_OPEN -> {
                    return CircuitBreakerPolicy.REJECTED;
                }
                case OPEN -> {
                    if (now - openedAt < waitInOpen) {
                        return CircuitBreakerPolicy.REJECTED;
                    }
                    generation++;
                    state = CircuitBreakerState.HALF_OPEN;
                    halfOpenSince = now;
                    permits = halfOpenCalls;
                    clearWindow();
                    return acquire(now);
                }
                default -> { // HALF_OPEN
                    if (maxWaitInHalfOpen > 0 && now - halfOpenSince >= maxWaitInHalfOpen) {
                        seen("half-open timed out");
                        open(now);
                        return CircuitBreakerPolicy.REJECTED;
                    }
                    if (permits == 0) {
                        return CircuitBreakerPolicy.REJECTED;
                    }
                    permits--;
                    return generation;
                }
            }
        }

        void complete(long token, Outcome outcome, boolean slow, long now) {
            if (token != generation) {
                seen("stale outcome dropped");
                return; // started in an earlier state
            }
            if (outcome == Outcome.IGNORED) {
                if (state == CircuitBreakerState.HALF_OPEN) {
                    seen("probe released");
                    permits++;
                }
                return;
            }
            boolean failed = outcome == Outcome.FAILURE;
            if (state == CircuitBreakerState.CLOSED) {
                if (timeWindow) {
                    long second = Math.floorDiv(now - windowOrigin, SECOND);
                    boolean firstOfItsSecond = secondsWithCalls.add(second);
                    timedOutcomes.add(new long[] {second, failed ? 1 : 0, slow ? 1 : 0});
                    // Documented: successes alone trigger an evaluation at most once per second.
                    if (!failed && !slow && firstOfItsSecond) {
                        seen("time window success evaluated");
                    }
                    if ((failed || slow || firstOfItsSecond) && exceeds(counts(now), minimumCalls)) {
                        opened("time", counts(now));
                        open(now);
                    }
                } else {
                    outcomes.addLast(new int[] {failed ? 1 : 0, slow ? 1 : 0});
                    if (outcomes.size() > windowSize) {
                        outcomes.removeFirst();
                    }
                    if (exceeds(counts(now), minimumCalls)) {
                        opened("count", counts(now));
                        open(now);
                    }
                }
            } else if (state == CircuitBreakerState.HALF_OPEN) {
                outcomes.addLast(new int[] {failed ? 1 : 0, slow ? 1 : 0});
                if (outcomes.size() == halfOpenCalls) {
                    if (exceeds(counts(now), halfOpenCalls)) {
                        seen("half-open opened");
                        open(now);
                    } else {
                        seen("half-open closed");
                        enterInitialClosed(now);
                    }
                }
            }
        }

        private void enterInitialClosed(long now) {
            generation++;
            windowOrigin = now;
            state = CircuitBreakerState.CLOSED;
            clearWindow();
        }

        private void opened(String window, int[] counts) {
            seen(window + " opened");
            if (counts[1] * 100.0 / counts[0] < failureThreshold) {
                seen(window + " opened by slow calls");
            }
        }

        /** At or above a threshold, once the window holds the minimum number of calls. */
        private boolean exceeds(int[] counts, int minimum) {
            int calls = counts[0];
            if (calls == 0 || calls < minimum) {
                return false;
            }
            return counts[1] * 100.0 / calls >= failureThreshold
                    || (timed && counts[2] * 100.0 / calls >= slowThreshold);
        }

        /** {calls, failures, slow calls} of the current window. */
        int[] counts(long now) {
            int[] counts = new int[3];
            if (state == CircuitBreakerState.CLOSED && timeWindow) {
                long second = Math.floorDiv(now - windowOrigin, SECOND);
                for (long[] call : timedOutcomes) {
                    if (call[0] > second - windowSeconds && call[0] <= second) {
                        counts[0]++;
                        counts[1] += (int) call[1];
                        counts[2] += (int) call[2];
                    }
                }
            } else if (state == CircuitBreakerState.CLOSED || state == CircuitBreakerState.HALF_OPEN) {
                for (int[] call : outcomes) {
                    counts[0]++;
                    counts[1] += call[0];
                    counts[2] += call[1];
                }
            }
            return counts;
        }

        void forceOpen() {
            generation++;
            state = CircuitBreakerState.FORCED_OPEN;
            clearWindow();
        }

        void disable() {
            generation++;
            state = CircuitBreakerState.DISABLED;
            clearWindow();
        }

        void reset(long now) {
            enterInitial(generation + 1, now);
        }

        CircuitBreakerSnapshot snapshot(long now) {
            int[] counts = counts(now);
            int minimum = switch (state) {
                case CLOSED -> minimumCalls;
                case HALF_OPEN -> halfOpenCalls;
                default -> 1;
            };
            int calls = counts[0];
            boolean evaluated = calls > 0 && calls >= minimum;
            double failureRate = evaluated ? counts[1] * 100.0 / calls : -1;
            double slowRate = evaluated && timed ? counts[2] * 100.0 / calls : -1;
            return new CircuitBreakerSnapshot("model", state, failureRate, slowRate, calls, counts[1], counts[2]);
        }
    }
}
