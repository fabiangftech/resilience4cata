package dev.resilience4cata;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

/**
 * Immutable adaptive limit configuration. Built with {@link #builder()} or {@link #toBuilder()}.
 *
 * <p>An adaptive limit caps the calls running at the same time, like a bulkhead, but moves the cap by
 * itself: after each window of {@code window} (and at least {@code minWindowSamples} completed calls), it
 * compares the window's average latency with what it has seen before and raises the limit while latency
 * holds, or lowers it when latency grows or calls are dropped ({@code dropOn}). The cap always stays
 * between {@code minLimit} and {@code maxLimit}.
 */
public final class AdaptiveLimitConfig implements PolicyConfig {

    private final boolean enabled;
    private final Algorithm algorithm;
    private final int initialLimit;
    private final int minLimit;
    private final int maxLimit;
    private final Duration window;
    private final int minWindowSamples;
    private final List<Class<? extends Throwable>> dropOn;
    private final List<Class<? extends Throwable>> ignoreOn;
    private final double smoothing;
    private final double rttTolerance;
    private final int longWindow;
    private final int queueSize;
    private final double backoffRatio;

    /**
     * How the limit is recomputed at the end of each window. Constants may be added in minor versions: keep a
     * {@code default} branch in a {@code switch} over it.
     */
    public enum Algorithm {
        /**
         * Compares the window's average latency with a long-term average (Netflix's {@code Gradient2Limit}):
         * the limit grows by {@code queueSize} while latency stays within {@code rttTolerance} of the
         * long-term average, and shrinks in proportion when it rises above it. Drops count only through
         * their latency. The default.
         */
        GRADIENT2,
        /**
         * Additive increase, multiplicative decrease (Netflix's {@code AIMDLimit}): one more slot after each
         * window without a drop in which at least half the limit was in use, and the limit times
         * {@code backoffRatio} after a window with a drop. Latency itself is not used.
         */
        AIMD
    }

    private static final Duration MIN_WINDOW = Duration.ofMillis(10);
    private static final Duration MAX_WINDOW = Duration.ofHours(1);

    /**
     * Built-in defaults: {@link Algorithm#GRADIENT2}, a limit starting at 20 and kept between 1 and 200 (Netflix's
     * minimum is 20; 1 lets {@link Algorithm#AIMD} go as low as the dependency needs, while Gradient2's practical
     * floor comes from {@code queueSize}),
     * windows of at least 1 s and 10 calls, drops on {@link CallTimeoutException}, {@link BulkheadFullException}
     * and {@link TimeoutException}, nothing ignored; Gradient2 smoothing 0.2, tolerance 1.5, long-term average
     * over 600 windows, queue size 4; AIMD backoff ratio 0.9.
     */
    public static final AdaptiveLimitConfig DEFAULTS = new AdaptiveLimitConfig(true, Algorithm.GRADIENT2, 20, 1, 200,
            Duration.ofSeconds(1), 10, List.of(CallTimeoutException.class, BulkheadFullException.class,
                    TimeoutException.class), List.of(), 0.2, 1.5, 600, 4, 0.9);

    /** The policy name in validation messages. */
    private static final String POLICY = "adaptiveLimit";

    /** Validates every value and copies the lists. */
    private AdaptiveLimitConfig(boolean enabled,
            Algorithm algorithm,
            int initialLimit,
            int minLimit,
            int maxLimit,
            Duration window,
            int minWindowSamples,
            List<Class<? extends Throwable>> dropOn,
            List<Class<? extends Throwable>> ignoreOn,
            double smoothing,
            double rttTolerance,
            int longWindow,
            int queueSize,
            double backoffRatio) {
        Objects.requireNonNull(algorithm, "algorithm");
        Objects.requireNonNull(window, "window");
        dropOn = List.copyOf(dropOn);
        ignoreOn = List.copyOf(ignoreOn);
        if (minLimit < 1) {
            throw ErrorCode.invalidValue(POLICY, "minLimit must be at least 1, was " + minLimit);
        }
        if (maxLimit < minLimit) {
            throw ErrorCode.invalidValue(POLICY, "maxLimit must be at least minLimit (" + minLimit + "), was " + maxLimit);
        }
        if (initialLimit < minLimit || initialLimit > maxLimit) {
            throw ErrorCode.invalidValue(POLICY, "initialLimit must be between minLimit (" + minLimit + ") and maxLimit ("
                    + maxLimit + "), was " + initialLimit);
        }
        if (window.compareTo(MIN_WINDOW) < 0 || window.compareTo(MAX_WINDOW) > 0) {
            throw ErrorCode.invalidValue(POLICY, "window must be between 10ms and 1h, was " + window);
        }
        if (minWindowSamples < 1) {
            throw ErrorCode.invalidValue(POLICY, "minWindowSamples must be at least 1, was " + minWindowSamples);
        }
        if (!(smoothing > 0 && smoothing <= 1)) {
            throw ErrorCode.invalidValue(POLICY, "smoothing must be above 0 and at most 1, was " + smoothing);
        }
        if (!(rttTolerance >= 1) || Double.isInfinite(rttTolerance)) {
            throw ErrorCode.invalidValue(POLICY, "rttTolerance must be a finite number >= 1, was " + rttTolerance);
        }
        if (longWindow < 1) {
            throw ErrorCode.invalidValue(POLICY, "longWindow must be at least 1, was " + longWindow);
        }
        if (queueSize < 0) {
            throw ErrorCode.invalidValue(POLICY, "queueSize must not be negative, was " + queueSize);
        }
        if (!(backoffRatio >= 0.5 && backoffRatio < 1)) {
            throw ErrorCode.invalidValue(POLICY, "backoffRatio must be at least 0.5 and below 1, was " + backoffRatio);
        }
        this.enabled = enabled;
        this.algorithm = algorithm;
        this.initialLimit = initialLimit;
        this.minLimit = minLimit;
        this.maxLimit = maxLimit;
        this.window = window;
        this.minWindowSamples = minWindowSamples;
        this.dropOn = dropOn;
        this.ignoreOn = ignoreOn;
        this.smoothing = smoothing;
        this.rttTolerance = rttTolerance;
        this.longWindow = longWindow;
        this.queueSize = queueSize;
        this.backoffRatio = backoffRatio;
    }

    /** Returns a builder that starts from {@link #DEFAULTS}. */
    public static Builder builder() {
        return new Builder(DEFAULTS);
    }

    /** Returns a builder that starts from this configuration. */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /** Mutable builder for {@link AdaptiveLimitConfig}. Values are validated by {@link #build()}. */
    public static final class Builder {
        private boolean enabled;
        private Algorithm algorithm;
        private int initialLimit;
        private int minLimit;
        private int maxLimit;
        private Duration window;
        private int minWindowSamples;
        private List<Class<? extends Throwable>> dropOn;
        private List<Class<? extends Throwable>> ignoreOn;
        private double smoothing;
        private double rttTolerance;
        private int longWindow;
        private int queueSize;
        private double backoffRatio;

        private Builder(AdaptiveLimitConfig from) {
            enabled = from.enabled;
            algorithm = from.algorithm;
            initialLimit = from.initialLimit;
            minLimit = from.minLimit;
            maxLimit = from.maxLimit;
            window = from.window;
            minWindowSamples = from.minWindowSamples;
            dropOn = from.dropOn;
            ignoreOn = from.ignoreOn;
            smoothing = from.smoothing;
            rttTolerance = from.rttTolerance;
            longWindow = from.longWindow;
            queueSize = from.queueSize;
            backoffRatio = from.backoffRatio;
        }

        /** @param enabled whether calls are limited at all @return this builder */
        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        /** @param algorithm how the limit is recomputed at the end of each window @return this builder */
        public Builder algorithm(Algorithm algorithm) {
            this.algorithm = Objects.requireNonNull(algorithm, "algorithm");
            return this;
        }

        /** @param initialLimit the limit before the first window ends @return this builder */
        public Builder initialLimit(int initialLimit) {
            this.initialLimit = initialLimit;
            return this;
        }

        /** @param minLimit the lowest the limit goes, at least 1 @return this builder */
        public Builder minLimit(int minLimit) {
            this.minLimit = minLimit;
            return this;
        }

        /** @param maxLimit the highest the limit goes @return this builder */
        public Builder maxLimit(int maxLimit) {
            this.maxLimit = maxLimit;
            return this;
        }

        /** @param window how long a window lasts at least, 10 ms to 1 h @return this builder */
        public Builder window(Duration window) {
            this.window = Objects.requireNonNull(window, "window");
            return this;
        }

        /** @param minWindowSamples completed calls a window needs before it ends @return this builder */
        public Builder minWindowSamples(int minWindowSamples) {
            this.minWindowSamples = minWindowSamples;
            return this;
        }

        /** @param dropOn failures that count as a drop: the dependency is overloaded @return this builder */
        public Builder dropOn(List<Class<? extends Throwable>> dropOn) {
            this.dropOn = List.copyOf(dropOn);
            return this;
        }

        /** @param ignoreOn failures that are never samples; they win over {@code dropOn} @return this builder */
        public Builder ignoreOn(List<Class<? extends Throwable>> ignoreOn) {
            this.ignoreOn = List.copyOf(ignoreOn);
            return this;
        }

        /** @param smoothing Gradient2: how much of each new estimate is kept, in (0, 1] @return this builder */
        public Builder smoothing(double smoothing) {
            this.smoothing = smoothing;
            return this;
        }

        /**
         * @param rttTolerance Gradient2: latency growth tolerated before the limit goes down, at least 1 @return
         * this builder
         */
        public Builder rttTolerance(double rttTolerance) {
            this.rttTolerance = rttTolerance;
            return this;
        }

        /** @param longWindow Gradient2: windows averaged into the long-term latency @return this builder */
        public Builder longWindow(int longWindow) {
            this.longWindow = longWindow;
            return this;
        }

        /** @param queueSize Gradient2: room left above the estimated concurrency @return this builder */
        public Builder queueSize(int queueSize) {
            this.queueSize = queueSize;
            return this;
        }

        /** @param backoffRatio AIMD: what a drop multiplies the limit by, in [0.5, 1) @return this builder */
        public Builder backoffRatio(double backoffRatio) {
            this.backoffRatio = backoffRatio;
            return this;
        }

        /**
         * Validates the values and builds the configuration.
         *
         * @return the configuration
         * @throws IllegalArgumentException if a value is invalid
         */
        public AdaptiveLimitConfig build() {
            return new AdaptiveLimitConfig(enabled, algorithm, initialLimit, minLimit, maxLimit, window,
                    minWindowSamples, dropOn, ignoreOn, smoothing, rttTolerance, longWindow, queueSize, backoffRatio);
        }
    }

    /**
     * Whether calls are limited at all.
     *
     * @return the value
     */
    @Override
    public boolean enabled() {
        return enabled;
    }

    /**
     * How the limit is recomputed at the end of each window.
     *
     * @return the value
     */
    public Algorithm algorithm() {
        return algorithm;
    }

    /**
     * The limit before the first window ends; between {@code minLimit} and {@code maxLimit}.
     *
     * @return the value
     */
    public int initialLimit() {
        return initialLimit;
    }

    /**
     * The lowest the limit goes; at least 1.
     *
     * @return the value
     */
    public int minLimit() {
        return minLimit;
    }

    /**
     * The highest the limit goes; at least {@code minLimit}.
     *
     * @return the value
     */
    public int maxLimit() {
        return maxLimit;
    }

    /**
     * How long a window lasts at least; between 10 ms and 1 h.
     *
     * @return the value
     */
    public Duration window() {
        return window;
    }

    /**
     * Completed calls a window needs before it ends; a window that is over but has fewer is extended by one more {@code
     * window} and keeps its calls, which count when it ends; at least 1.
     *
     * @return the value
     */
    public int minWindowSamples() {
        return minWindowSamples;
    }

    /**
     * Failures that mean the dependency is overloaded (a timeout, a full bulkhead): they count as a sample with a drop,
     * which makes {@link Algorithm#AIMD} back off. Any other failure is ignored: the slot is freed and the call is not
     * a sample.
     *
     * @return the value
     */
    public List<Class<? extends Throwable>> dropOn() {
        return dropOn;
    }

    /**
     * Failures that are never samples, even if listed in {@code dropOn}; they win over it.
     *
     * @return the value
     */
    public List<Class<? extends Throwable>> ignoreOn() {
        return ignoreOn;
    }

    /**
     * {@link Algorithm#GRADIENT2}: how much of each new estimate is kept, above 0 and at most 1.
     *
     * @return the value
     */
    public double smoothing() {
        return smoothing;
    }

    /**
     * {@link Algorithm#GRADIENT2}: how many times the long-term latency the window's latency may reach before the limit
     * goes down; finite, at least 1.
     *
     * @return the value
     */
    public double rttTolerance() {
        return rttTolerance;
    }

    /**
     * {@link Algorithm#GRADIENT2}: windows averaged into the long-term latency; at least 1.
     *
     * @return the value
     */
    public int longWindow() {
        return longWindow;
    }

    /**
     * {@link Algorithm#GRADIENT2}: calls the limit leaves room for above the estimated concurrency, so that it can
     * grow; at least 0.
     *
     * @return the value
     */
    public int queueSize() {
        return queueSize;
    }

    /**
     * {@link Algorithm#AIMD}: what a drop multiplies the limit by; at least 0.5 and below 1.
     *
     * @return the value
     */
    public double backoffRatio() {
        return backoffRatio;
    }

    /** Equal when every value is equal. */
    @Override
    public boolean equals(Object o) {
        return o instanceof AdaptiveLimitConfig other
                && enabled == other.enabled
                && algorithm.equals(other.algorithm)
                && initialLimit == other.initialLimit
                && minLimit == other.minLimit
                && maxLimit == other.maxLimit
                && window.equals(other.window)
                && minWindowSamples == other.minWindowSamples
                && dropOn.equals(other.dropOn)
                && ignoreOn.equals(other.ignoreOn)
                && Double.compare(smoothing, other.smoothing) == 0
                && Double.compare(rttTolerance, other.rttTolerance) == 0
                && longWindow == other.longWindow
                && queueSize == other.queueSize
                && Double.compare(backoffRatio, other.backoffRatio) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(enabled, algorithm, initialLimit, minLimit, maxLimit, window, minWindowSamples, dropOn,
                ignoreOn, smoothing, rttTolerance, longWindow, queueSize, backoffRatio);
    }

    /** The values, as {@code AdaptiveLimitConfig[name=value, ...]}. */
    @Override
    public String toString() {
        return "AdaptiveLimitConfig[enabled=" + enabled + ", algorithm=" + algorithm
                + ", initialLimit=" + initialLimit + ", minLimit=" + minLimit + ", maxLimit=" + maxLimit
                + ", window=" + window + ", minWindowSamples=" + minWindowSamples + ", dropOn=" + dropOn
                + ", ignoreOn=" + ignoreOn + ", smoothing=" + smoothing + ", rttTolerance=" + rttTolerance
                + ", longWindow=" + longWindow + ", queueSize=" + queueSize + ", backoffRatio=" + backoffRatio + "]";
    }

    /** The values in declaration order, for {@link ResilienceSnapshot#toMap()}. */
    java.util.Map<String, Object> attributes() {
        java.util.Map<String, Object> values = new java.util.LinkedHashMap<>();
        values.put("enabled", enabled);
        values.put("algorithm", algorithm);
        values.put("initialLimit", initialLimit);
        values.put("minLimit", minLimit);
        values.put("maxLimit", maxLimit);
        values.put("window", window);
        values.put("minWindowSamples", minWindowSamples);
        values.put("dropOn", dropOn);
        values.put("ignoreOn", ignoreOn);
        values.put("smoothing", smoothing);
        values.put("rttTolerance", rttTolerance);
        values.put("longWindow", longWindow);
        values.put("queueSize", queueSize);
        values.put("backoffRatio", backoffRatio);
        return values;
    }
}
