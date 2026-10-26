package dev.resilience4cata.generated;

import dev.resilience4cata.AdaptiveLimitConfig;

/**
 * Recomputes an adaptive limit at the end of each window. Called only by the thread that ends the
 * window, which holds {@link AdaptiveLimitPolicy}'s window lock, so implementations keep plain mutable
 * state: each call happens after the previous one.
 *
 * <p>Both algorithms follow Netflix's concurrency-limits 0.5.4 ({@code Gradient2Limit}, {@code AIMDLimit}),
 * fed like its {@code WindowedLimit} with one sample per window: the window's average latency, the most
 * calls in flight seen in it, and whether any call was dropped.
 */
abstract sealed class LimitAlgorithm {

    final int minLimit;
    final int maxLimit;

    LimitAlgorithm(AdaptiveLimitConfig config) {
        this.minLimit = config.minLimit();
        this.maxLimit = config.maxLimit();
    }

    static LimitAlgorithm of(AdaptiveLimitConfig config) {
        return switch (config.algorithm()) {
            case GRADIENT2 -> new Gradient2(config);
            case AIMD -> new Aimd(config);
        };
    }

    /**
     * Returns the next limit.
     *
     * @param current the limit now
     * @param rttNanos the window's average latency, at least 1
     * @param maxInFlight the most calls in flight seen in the window
     * @param dropped whether a call of the window was dropped
     * @return the limit from now on, between {@code minLimit} and {@code maxLimit}
     */
    abstract int update(int current, long rttNanos, int maxInFlight, boolean dropped);

    /** The long-term latency this algorithm compares windows with, in nanoseconds; 0 if it has none. */
    abstract double longTermNanos();

    /**
     * Netflix's {@code Gradient2Limit}. The limit is kept as a {@code double} estimate; the long-term
     * latency is an exponential average over {@code longWindow} windows, a plain average for the first 10.
     */
    static final class Gradient2 extends LimitAlgorithm {

        private static final int WARMUP = 10;

        private final double smoothing;
        private final double tolerance;
        private final int queueSize;
        private final double factor;
        private double estimatedLimit;
        private double longRtt;
        private double warmupSum;
        private int warmupCount;

        Gradient2(AdaptiveLimitConfig config) {
            super(config);
            this.smoothing = config.smoothing();
            this.tolerance = config.rttTolerance();
            this.queueSize = config.queueSize();
            this.factor = 2.0 / (config.longWindow() + 1);
            this.estimatedLimit = config.initialLimit();
        }

        @Override
        int update(int current, long rttNanos, int maxInFlight, boolean dropped) {
            double shortRtt = rttNanos;
            double longTerm = addLongRtt(shortRtt);
            // Latency back to normal after a long overload: bring the long-term average down faster than its
            // smoothing would.
            if (longTerm / shortRtt > 2) {
                longRtt = longTerm * 0.95;
            }
            // Application-limited: fewer calls than half the limit, so the latency says nothing about it.
            if (maxInFlight < estimatedLimit / 2) {
                return (int) estimatedLimit;
            }
            // Never cut by more than half at once, so that one slow window does not shed most of the load.
            double gradient = Math.max(0.5, Math.min(1.0, tolerance * longTerm / shortRtt));
            double next = estimatedLimit * gradient + queueSize;
            next = estimatedLimit * (1 - smoothing) + next * smoothing;
            estimatedLimit = Math.max(minLimit, Math.min(maxLimit, next));
            return (int) estimatedLimit;
        }

        private double addLongRtt(double sample) {
            if (warmupCount < WARMUP) {
                warmupCount++;
                warmupSum += sample;
                longRtt = warmupSum / warmupCount;
            } else {
                longRtt = longRtt * (1 - factor) + sample * factor;
            }
            return longRtt;
        }

        @Override
        double longTermNanos() {
            return longRtt;
        }
    }

    /** Netflix's {@code AIMDLimit}: one more after a busy window without a drop, times {@code backoffRatio} after a drop. */
    static final class Aimd extends LimitAlgorithm {

        private final double backoffRatio;

        Aimd(AdaptiveLimitConfig config) {
            super(config);
            this.backoffRatio = config.backoffRatio();
        }

        @Override
        int update(int current, long rttNanos, int maxInFlight, boolean dropped) {
            int next = current;
            if (dropped) {
                next = (int) (current * backoffRatio);
            } else if (maxInFlight * 2L >= current) {
                next = current + 1;
            }
            return Math.min(maxLimit, Math.max(minLimit, next));
        }

        @Override
        double longTermNanos() {
            return 0;
        }
    }
}
