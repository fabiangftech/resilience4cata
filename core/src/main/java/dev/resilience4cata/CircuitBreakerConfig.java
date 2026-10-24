package dev.resilience4cata;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Immutable circuit breaker configuration. Built with {@link #builder()} or {@link #toBuilder()}.
 *
 * @param enabled whether the breaker records calls and can open
 * @param windowType how the sliding window is measured
 * @param windowSize number of calls ({@link WindowType#COUNT}) or seconds ({@link WindowType#TIME}) in the window
 * @param minimumCalls calls needed before the rates are evaluated; capped at {@code windowSize} for a count window
 * @param failureRateThreshold failure rate, in percent, that opens the breaker
 * @param slowCallRateThreshold slow-call rate, in percent, that opens the breaker
 * @param slowCallDuration duration above which a call is slow; {@link Duration#ZERO} turns slow-call
 *     detection off, so calls are not timed at all
 * @param waitInOpen how long the breaker stays open before allowing probe calls
 * @param halfOpenCalls number of probe calls in the half-open state
 * @param maxWaitInHalfOpen how long the breaker may stay half-open before opening again if its probes
 *     have not completed; {@link Duration#ZERO} means no limit
 * @param recordOn exceptions recorded as failures; other exceptions count as successes
 * @param ignoreOn exceptions that count neither as success nor as failure; they win over {@code recordOn}
 */
public record CircuitBreakerConfig(
        boolean enabled,
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

    /**
     * Built-in defaults, the same as Resilience4j's except that slow-call detection is off: a window
     * of 100 calls, evaluated after 100 calls, opening at 50% failures, 60 s open, 10 probes, no limit on
     * the time spent half-open.
     */
    public static final CircuitBreakerConfig DEFAULTS = new CircuitBreakerConfig(
            true, WindowType.COUNT, 100, 100, 50f, 100f, Duration.ZERO, Duration.ofSeconds(60), 10,
            Duration.ZERO, List.of(Exception.class), List.of());

    /** How the sliding window is measured. */
    public enum WindowType {
        /** The last {@code windowSize} calls. */
        COUNT,
        /**
         * The calls of the last {@code windowSize} seconds, in one-second buckets. Every call reads the
         * clock, and successes alone trigger an evaluation at most once per second.
         */
        TIME
    }

    /** Validates every value and copies the exception lists. */
    public CircuitBreakerConfig {
        Objects.requireNonNull(windowType, "windowType");
        Objects.requireNonNull(slowCallDuration, "slowCallDuration");
        Objects.requireNonNull(waitInOpen, "waitInOpen");
        Objects.requireNonNull(maxWaitInHalfOpen, "maxWaitInHalfOpen");
        recordOn = List.copyOf(recordOn);
        ignoreOn = List.copyOf(ignoreOn);
        if (windowSize < 1) {
            throw new IllegalArgumentException("windowSize must be at least 1, was " + windowSize);
        }
        if (minimumCalls < 1) {
            throw new IllegalArgumentException("minimumCalls must be at least 1, was " + minimumCalls);
        }
        checkRate("failureRateThreshold", failureRateThreshold);
        checkRate("slowCallRateThreshold", slowCallRateThreshold);
        if (slowCallDuration.isNegative()) {
            throw new IllegalArgumentException("slowCallDuration must not be negative, was " + slowCallDuration);
        }
        if (waitInOpen.isNegative()) {
            throw new IllegalArgumentException("waitInOpen must not be negative, was " + waitInOpen);
        }
        if (halfOpenCalls < 1) {
            throw new IllegalArgumentException("halfOpenCalls must be at least 1, was " + halfOpenCalls);
        }
        if (maxWaitInHalfOpen.isNegative()) {
            throw new IllegalArgumentException("maxWaitInHalfOpen must not be negative, was " + maxWaitInHalfOpen);
        }
    }

    private static void checkRate(String name, float rate) {
        if (!(rate > 0 && rate <= 100)) {
            throw new IllegalArgumentException(name + " must be a percentage > 0 and <= 100, was " + rate);
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

        /** @param slowCallDuration duration above which a call is slow; zero turns detection off @return this builder */
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
}
