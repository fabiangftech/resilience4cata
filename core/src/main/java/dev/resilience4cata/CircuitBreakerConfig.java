package dev.resilience4cata;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Immutable circuit breaker configuration. Built with {@link #builder()} or {@link #toBuilder()}.
 */
public final class CircuitBreakerConfig implements PolicyConfig {

    private final boolean enabled;
    private final WindowType windowType;
    private final int windowSize;
    private final int minimumCalls;
    private final float failureRateThreshold;
    private final float slowCallRateThreshold;
    private final Duration slowCallDuration;
    private final Duration waitInOpen;
    private final int halfOpenCalls;
    private final Duration maxWaitInHalfOpen;
    private final List<Class<? extends Throwable>> recordOn;
    private final List<Class<? extends Throwable>> ignoreOn;

    /**
     * Built-in defaults, the same as Resilience4j's except that slow-call detection is off: a window
     * of 100 calls, evaluated after 100 calls, opening at 50% failures, 60 s open, 10 probes, no limit on
     * the time spent half-open.
     */
    public static final CircuitBreakerConfig DEFAULTS = new CircuitBreakerConfig(
            true, WindowType.COUNT, 100, 100, 50f, 100f, Duration.ZERO, Duration.ofSeconds(60), 10,
            Duration.ZERO, List.of(Exception.class), List.of());

    /**
     * How the sliding window is measured. Constants may be added in minor versions: keep a {@code default} branch
     * in a {@code switch} over it.
     */
    public enum WindowType {
        /** The last {@code windowSize} calls. */
        COUNT,
        /**
         * The calls of the last {@code windowSize} seconds, in one-second buckets. Every call reads the
         * clock, and successes alone trigger an evaluation at most once per second.
         */
        TIME
    }

    /** The policy name in validation messages. */
    private static final String POLICY = "circuitBreaker";

    /** Validates every value and copies the lists. */
    private CircuitBreakerConfig(boolean enabled,
            WindowType windowType,
            int windowSize,
            int minimumCalls,
            float failureRateThreshold,
            float slowCallRateThreshold,
            Duration slowCallDuration,
            Duration waitInOpen,
            int halfOpenCalls,
            Duration maxWaitInHalfOpen,
            List<Class<? extends Throwable>> recordOn,
            List<Class<? extends Throwable>> ignoreOn) {
        Objects.requireNonNull(windowType, "windowType");
        Objects.requireNonNull(slowCallDuration, "slowCallDuration");
        Objects.requireNonNull(waitInOpen, "waitInOpen");
        Objects.requireNonNull(maxWaitInHalfOpen, "maxWaitInHalfOpen");
        recordOn = List.copyOf(recordOn);
        ignoreOn = List.copyOf(ignoreOn);
        if (windowSize < 1) {
            throw ErrorCode.invalidValue(POLICY, "windowSize must be at least 1, was " + windowSize);
        }
        if (minimumCalls < 1) {
            throw ErrorCode.invalidValue(POLICY, "minimumCalls must be at least 1, was " + minimumCalls);
        }
        checkRate("failureRateThreshold", failureRateThreshold);
        checkRate("slowCallRateThreshold", slowCallRateThreshold);
        if (slowCallDuration.isNegative()) {
            throw ErrorCode.invalidValue(POLICY, "slowCallDuration must not be negative, was " + slowCallDuration);
        }
        if (waitInOpen.isNegative()) {
            throw ErrorCode.invalidValue(POLICY, "waitInOpen must not be negative, was " + waitInOpen);
        }
        if (halfOpenCalls < 1) {
            throw ErrorCode.invalidValue(POLICY, "halfOpenCalls must be at least 1, was " + halfOpenCalls);
        }
        if (maxWaitInHalfOpen.isNegative()) {
            throw ErrorCode.invalidValue(POLICY, "maxWaitInHalfOpen must not be negative, was " + maxWaitInHalfOpen);
        }
        this.enabled = enabled;
        this.windowType = windowType;
        this.windowSize = windowSize;
        this.minimumCalls = minimumCalls;
        this.failureRateThreshold = failureRateThreshold;
        this.slowCallRateThreshold = slowCallRateThreshold;
        this.slowCallDuration = slowCallDuration;
        this.waitInOpen = waitInOpen;
        this.halfOpenCalls = halfOpenCalls;
        this.maxWaitInHalfOpen = maxWaitInHalfOpen;
        this.recordOn = recordOn;
        this.ignoreOn = ignoreOn;
    }

    private static void checkRate(String name, float rate) {
        if (!(rate > 0 && rate <= 100)) {
            throw ErrorCode.invalidValue(POLICY, name + " must be a percentage > 0 and <= 100, was " + rate);
        }
    }

    /** Returns a builder that starts from {@link #DEFAULTS}. */
    public static Builder builder() {
        return new Builder(DEFAULTS);
    }

    /** Returns a builder that starts from this configuration. */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /** Mutable builder for {@link CircuitBreakerConfig}. Values are validated by {@link #build()}. */
    public static final class Builder {
        private boolean enabled;
        private WindowType windowType;
        private int windowSize;
        private int minimumCalls;
        private float failureRateThreshold;
        private float slowCallRateThreshold;
        private Duration slowCallDuration;
        private Duration waitInOpen;
        private int halfOpenCalls;
        private Duration maxWaitInHalfOpen;
        private List<Class<? extends Throwable>> recordOn;
        private List<Class<? extends Throwable>> ignoreOn;

        private Builder(CircuitBreakerConfig from) {
            enabled = from.enabled;
            windowType = from.windowType;
            windowSize = from.windowSize;
            minimumCalls = from.minimumCalls;
            failureRateThreshold = from.failureRateThreshold;
            slowCallRateThreshold = from.slowCallRateThreshold;
            slowCallDuration = from.slowCallDuration;
            waitInOpen = from.waitInOpen;
            halfOpenCalls = from.halfOpenCalls;
            maxWaitInHalfOpen = from.maxWaitInHalfOpen;
            recordOn = from.recordOn;
            ignoreOn = from.ignoreOn;
        }

        /** @param enabled whether the breaker records calls and can open @return this builder */
        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        /** @param windowType how the sliding window is measured @return this builder */
        public Builder windowType(WindowType windowType) {
            this.windowType = Objects.requireNonNull(windowType, "windowType");
            return this;
        }

        /** @param windowSize number of calls in the sliding window @return this builder */
        public Builder windowSize(int windowSize) {
            this.windowSize = windowSize;
            return this;
        }

        /** @param minimumCalls calls needed before the rates are evaluated @return this builder */
        public Builder minimumCalls(int minimumCalls) {
            this.minimumCalls = minimumCalls;
            return this;
        }

        /** @param failureRateThreshold failure rate, in percent, that opens the breaker @return this builder */
        public Builder failureRateThreshold(float failureRateThreshold) {
            this.failureRateThreshold = failureRateThreshold;
            return this;
        }

        /** @param slowCallRateThreshold slow-call rate, in percent, that opens the breaker @return this builder */
        public Builder slowCallRateThreshold(float slowCallRateThreshold) {
            this.slowCallRateThreshold = slowCallRateThreshold;
            return this;
        }

        /**
         * @param slowCallDuration duration above which a call is slow; zero turns detection off @return this
         * builder
         */
        public Builder slowCallDuration(Duration slowCallDuration) {
            this.slowCallDuration = Objects.requireNonNull(slowCallDuration, "slowCallDuration");
            return this;
        }

        /** @param waitInOpen how long the breaker stays open before probing @return this builder */
        public Builder waitInOpen(Duration waitInOpen) {
            this.waitInOpen = Objects.requireNonNull(waitInOpen, "waitInOpen");
            return this;
        }

        /** @param halfOpenCalls number of probe calls in the half-open state @return this builder */
        public Builder halfOpenCalls(int halfOpenCalls) {
            this.halfOpenCalls = halfOpenCalls;
            return this;
        }

        /** @param maxWaitInHalfOpen maximum time half-open; zero means no limit @return this builder */
        public Builder maxWaitInHalfOpen(Duration maxWaitInHalfOpen) {
            this.maxWaitInHalfOpen = Objects.requireNonNull(maxWaitInHalfOpen, "maxWaitInHalfOpen");
            return this;
        }

        /** @param recordOn exceptions recorded as failures @return this builder */
        public Builder recordOn(List<Class<? extends Throwable>> recordOn) {
            this.recordOn = List.copyOf(recordOn);
            return this;
        }

        /** @param ignoreOn exceptions that are not counted @return this builder */
        public Builder ignoreOn(List<Class<? extends Throwable>> ignoreOn) {
            this.ignoreOn = List.copyOf(ignoreOn);
            return this;
        }

        /**
         * Validates the values and builds the configuration.
         *
         * @return the configuration
         * @throws IllegalArgumentException if a value is invalid
         */
        public CircuitBreakerConfig build() {
            return new CircuitBreakerConfig(enabled, windowType, windowSize, minimumCalls, failureRateThreshold,
                    slowCallRateThreshold, slowCallDuration, waitInOpen, halfOpenCalls, maxWaitInHalfOpen, recordOn, ignoreOn);
        }
    }

    /**
     * Whether the breaker records calls and can open.
     *
     * @return the value
     */
    @Override
    public boolean enabled() {
        return enabled;
    }

    /**
     * How the sliding window is measured.
     *
     * @return the value
     */
    public WindowType windowType() {
        return windowType;
    }

    /**
     * Number of calls ({@link WindowType#COUNT}) or seconds ({@link WindowType#TIME}) in the window.
     *
     * @return the value
     */
    public int windowSize() {
        return windowSize;
    }

    /**
     * Calls needed before the rates are evaluated; capped at {@code windowSize} for a count window.
     *
     * @return the value
     */
    public int minimumCalls() {
        return minimumCalls;
    }

    /**
     * Failure rate, in percent, that opens the breaker.
     *
     * @return the value
     */
    public float failureRateThreshold() {
        return failureRateThreshold;
    }

    /**
     * Slow-call rate, in percent, that opens the breaker.
     *
     * @return the value
     */
    public float slowCallRateThreshold() {
        return slowCallRateThreshold;
    }

    /**
     * Duration above which a call is slow; {@link Duration#ZERO} turns slow-call detection off, so calls are not timed
     * at all.
     *
     * @return the value
     */
    public Duration slowCallDuration() {
        return slowCallDuration;
    }

    /**
     * How long the breaker stays open before allowing probe calls.
     *
     * @return the value
     */
    public Duration waitInOpen() {
        return waitInOpen;
    }

    /**
     * Number of probe calls in the half-open state.
     *
     * @return the value
     */
    public int halfOpenCalls() {
        return halfOpenCalls;
    }

    /**
     * How long the breaker may stay half-open before opening again if its probes have not completed; {@link
     * Duration#ZERO} means no limit.
     *
     * @return the value
     */
    public Duration maxWaitInHalfOpen() {
        return maxWaitInHalfOpen;
    }

    /**
     * Exceptions recorded as failures; other exceptions count as successes.
     *
     * @return the value
     */
    public List<Class<? extends Throwable>> recordOn() {
        return recordOn;
    }

    /**
     * Exceptions that count neither as success nor as failure; they win over {@code recordOn}.
     *
     * @return the value
     */
    public List<Class<? extends Throwable>> ignoreOn() {
        return ignoreOn;
    }

    /** Equal when every value is equal. */
    @Override
    public boolean equals(Object o) {
        return o instanceof CircuitBreakerConfig other
                && enabled == other.enabled
                && windowType.equals(other.windowType)
                && windowSize == other.windowSize
                && minimumCalls == other.minimumCalls
                && Float.compare(failureRateThreshold, other.failureRateThreshold) == 0
                && Float.compare(slowCallRateThreshold, other.slowCallRateThreshold) == 0
                && slowCallDuration.equals(other.slowCallDuration)
                && waitInOpen.equals(other.waitInOpen)
                && halfOpenCalls == other.halfOpenCalls
                && maxWaitInHalfOpen.equals(other.maxWaitInHalfOpen)
                && recordOn.equals(other.recordOn)
                && ignoreOn.equals(other.ignoreOn);
    }

    @Override
    public int hashCode() {
        return Objects.hash(enabled, windowType, windowSize, minimumCalls, failureRateThreshold,
                slowCallRateThreshold, slowCallDuration, waitInOpen, halfOpenCalls, maxWaitInHalfOpen, recordOn,
                ignoreOn);
    }

    /** The values, as {@code CircuitBreakerConfig[name=value, ...]}. */
    @Override
    public String toString() {
        return "CircuitBreakerConfig[enabled=" + enabled + ", windowType=" + windowType + ", windowSize=" + windowSize
                + ", minimumCalls=" + minimumCalls + ", failureRateThreshold=" + failureRateThreshold
                + ", slowCallRateThreshold=" + slowCallRateThreshold + ", slowCallDuration=" + slowCallDuration
                + ", waitInOpen=" + waitInOpen + ", halfOpenCalls=" + halfOpenCalls
                + ", maxWaitInHalfOpen=" + maxWaitInHalfOpen + ", recordOn=" + recordOn
                + ", ignoreOn=" + ignoreOn + "]";
    }

    /** The values in declaration order, for {@link ResilienceSnapshot#toMap()}. */
    java.util.Map<String, Object> attributes() {
        java.util.Map<String, Object> values = new java.util.LinkedHashMap<>();
        values.put("enabled", enabled);
        values.put("windowType", windowType);
        values.put("windowSize", windowSize);
        values.put("minimumCalls", minimumCalls);
        values.put("failureRateThreshold", failureRateThreshold);
        values.put("slowCallRateThreshold", slowCallRateThreshold);
        values.put("slowCallDuration", slowCallDuration);
        values.put("waitInOpen", waitInOpen);
        values.put("halfOpenCalls", halfOpenCalls);
        values.put("maxWaitInHalfOpen", maxWaitInHalfOpen);
        values.put("recordOn", recordOn);
        values.put("ignoreOn", ignoreOn);
        return values;
    }
}
