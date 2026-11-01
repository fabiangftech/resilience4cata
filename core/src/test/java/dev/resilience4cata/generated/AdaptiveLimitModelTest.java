package dev.resilience4cata.generated;

import dev.resilience4cata.AdaptiveLimitConfig;
import dev.resilience4cata.AdaptiveLimitFullException;
import dev.resilience4cata.AdaptiveLimitSnapshot;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * Drives {@link AdaptiveLimitPolicy} through random sequences of calls that start, succeed, are dropped,
 * fail otherwise, are ignored or cancelled, and clock moves, and compares it after every step with a model
 * of docs/en/policies/adaptive-limit.md: the calls in flight (never negative, a call rejected only when they
 * reach the limit), the windows (a completion at or after the deadline closes the window if it has
 * {@code minWindowSamples} samples, else only moves the deadline), each window's average latency, and the
 * limit, which stays within {@code [minLimit, maxLimit]} and moves only when a window closes. AIMD is modelled
 * exactly; Gradient2's arithmetic has its own tests ({@code LimitAlgorithmTest}), so for it only the
 * invariants are checked. See {@link ModelRuns} to replay a seed.
 */
class AdaptiveLimitModelTest {

    private static final long MILLI = 1_000_000L;
    private static final int SEEDS = 1500;
    private static final int STEPS = 500;

    @Test
    void matchesTheModel() {
        ModelRuns.forEachSeed(SEEDS, AdaptiveLimitModelTest::run);
    }

    private static void run(long seed) {
        SplittableRandom random = new SplittableRandom(seed);
        int minLimit = random.nextInt(1, 5);
        int maxLimit = minLimit + random.nextInt(0, 20);
        boolean aimd = random.nextBoolean();
        AdaptiveLimitConfig config = AdaptiveLimitConfig.builder()
                .enabled(random.nextInt(20) != 0)
                .algorithm(aimd ? AdaptiveLimitConfig.Algorithm.AIMD : AdaptiveLimitConfig.Algorithm.GRADIENT2)
                .minLimit(minLimit).maxLimit(maxLimit).initialLimit(random.nextInt(minLimit, maxLimit + 1))
                .window(Duration.ofMillis(random.nextInt(10, 500)))
                .minWindowSamples(random.nextInt(1, 8))
                .backoffRatio(List.of(0.5, 0.75, 0.9).get(random.nextInt(3)))
                .queueSize(random.nextInt(1, 6))
                .dropOn(List.of(IOException.class))
                .ignoreOn(List.of(FileNotFoundException.class))
                .build();
        long start = switch (random.nextInt(3)) {
            case 0 -> 0L;
            case 1 -> -random.nextLong(1L << 50);
            default -> Long.MAX_VALUE - random.nextLong(2_000 * MILLI); // wraps during the run
        };
        ModelRuns.Clock clock = new ModelRuns.Clock(start);
        AdaptiveLimitPolicy limiter = new AdaptiveLimitPolicy("model", config,
                PolicyEnvironment.forTest(clock::read, RetryPolicy.Sleeper.NONE), 4);
        ModelRuns.Trace trace = new ModelRuns.Trace(seed, config + ", start " + start);
        Model model = new Model(config, start);
        List<Long> pending = new ArrayList<>(); // start of each call in flight

        for (int step = 0; step < STEPS; step++) {
            int op = random.nextInt(100);
            if (op < 40 || pending.isEmpty() && op < 70) {
                int limit = limiter.limit();
                boolean passed;
                long callStart = 0;
                try {
                    callStart = limiter.acquire();
                    passed = true;
                } catch (AdaptiveLimitFullException e) {
                    passed = false;
                }
                trace.step("acquire at " + clock.now + " -> " + (passed ? "passed" : "rejected"));
                trace.equal("acquire", !model.enabled || model.inFlight < limit, passed);
                if (passed) {
                    trace.equal("start", model.enabled ? clock.now : 0L, callStart);
                    pending.add(callStart);
                    model.inFlight += model.enabled ? 1 : 0;
                }
            } else if (op < 75 && !pending.isEmpty()) {
                long callStart = pending.remove(random.nextInt(pending.size()));
                int how = random.nextInt(6);
                switch (how) {
                    case 0, 1 -> limiter.onSuccess(callStart);
                    case 2 -> limiter.onFailure(callStart, new IOException("dropped"));
                    case 3 -> limiter.onFailure(callStart, new FileNotFoundException("ignored"));
                    case 4 -> limiter.onFailure(callStart, new IllegalStateException("other"));
                    default -> limiter.release();
                }
                trace.step(List.of("success", "success", "dropped", "ignored", "other failure", "cancelled").get(how)
                        + " of the call started at " + callStart + ", at " + clock.now);
                model.complete(how <= 1 ? Model.SAMPLE : how == 2 ? Model.DROP : Model.NO_SAMPLE, callStart, clock.now,
                        limiter.limit());
            } else {
                long by = switch (random.nextInt(4)) {
                    case 0 -> 0L;
                    case 1 -> random.nextLong(1, 10 * MILLI);
                    case 2 -> Math.max(0, model.deadline - clock.now + random.nextInt(3) - 1);
                    default -> random.nextLong(10 * MILLI, 1_000 * MILLI);
                };
                by = by <= 10_000 * MILLI ? by : 0; // by subtraction: the clock may wrap
                clock.now += by;
                trace.step("advance " + by + " to " + clock.now);
            }
            AdaptiveLimitSnapshot snapshot = limiter.snapshot();
            trace.equal("concurrentCalls", model.inFlight, limiter.concurrentCalls());
            trace.equal("windows", model.windows, snapshot.windows());
            trace.equal("window latency", Duration.ofNanos(model.windowLatency), snapshot.windowLatency());
            trace.check(limiter.limit() >= minLimit && limiter.limit() <= maxLimit,
                    "limit " + limiter.limit() + " outside [" + minLimit + ", " + maxLimit + "]");
            trace.equal("limit (moves only when a window closes)", model.limit, limiter.limit());
        }
    }

    private static final class Model {
        static final int SAMPLE = 0;
        static final int DROP = 1;
        static final int NO_SAMPLE = 2;

        final boolean enabled;
        final boolean aimd;
        final long window;
        final int minWindowSamples;
        final int minLimit;
        final int maxLimit;
        final double backoffRatio;
        int limit;
        int inFlight;
        long deadline;
        long windows;
        long windowLatency;
        /** Samples not yet used by a window: count, latency sum, drops, most calls in flight. */
        long samples;
        long latencySum;
        long drops;
        int maxInFlight;

        Model(AdaptiveLimitConfig config, long now) {
            enabled = config.enabled();
            aimd = config.algorithm() == AdaptiveLimitConfig.Algorithm.AIMD;
            window = config.window().toNanos();
            minWindowSamples = config.minWindowSamples();
            minLimit = config.minLimit();
            maxLimit = config.maxLimit();
            backoffRatio = config.backoffRatio();
            limit = config.initialLimit();
            deadline = now + window;
        }

        /**
         * @param policyLimit the policy's limit after this completion; for Gradient2 the model takes it as
         *     the new limit when a window closes (the invariants are checked by the caller)
         */
        void complete(int kind, long start, long now, int policyLimit) {
            if (!enabled) {
                return;
            }
            int before = inFlight;
            inFlight--;
            if (kind == NO_SAMPLE) {
                return;
            }
            samples++;
            latencySum += Math.max(0, now - start);
            drops += kind == DROP ? 1 : 0;
            maxInFlight = Math.max(maxInFlight, before);
            if (now - deadline < 0) {
                return;
            }
            deadline = now + window;
            if (samples < minWindowSamples) {
                return; // not enough samples: they count in the next window
            }
            windows++;
            windowLatency = Math.max(1, latencySum / samples);
            if (aimd) {
                int next = limit;
                if (drops > 0) {
                    next = (int) (limit * backoffRatio);
                } else if (maxInFlight * 2L >= limit) {
                    next = limit + 1;
                }
                limit = Math.min(maxLimit, Math.max(minLimit, next));
            } else {
                limit = policyLimit;
            }
            samples = 0;
            latencySum = 0;
            drops = 0;
            maxInFlight = 0;
        }
    }
}
