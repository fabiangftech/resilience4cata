package dev.resilience4cata;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable rate limiter configuration. Built with {@link #builder()} or {@link #toBuilder()}.
 *
 * <p>The limiter lets through {@code permits} calls per {@code period} on average, spaced
 * {@code period / permits} apart. Unused permits build up to a burst of {@code permits} calls, so
 * after a quiet period up to {@code permits} calls pass at once. As with Resilience4j's fixed cycles,
 * a window of one {@code period} can therefore see up to twice {@code permits} calls: a full burst,
 * then the steady rate.
 */
public final class RateLimiterConfig implements PolicyConfig {

    private final boolean enabled;
    private final int permits;
    private final Duration period;
    private final Duration maxWait;

    /**
     * Built-in defaults: 50 calls per second, no waiting. Resilience4j also allows 50 per period, but
     * with a 500 ns period and a 5 s wait; neither is a sensible default for a limiter that is meant
     * to protect a remote service, and waiting is opt-in here, as in the bulkhead.
     */
    public static final RateLimiterConfig DEFAULTS = new RateLimiterConfig(true, 50, Duration.ofSeconds(1), Duration.ZERO);

    /** The policy name in validation messages. */
    private static final String POLICY = "rateLimiter";

    /** Validates every value. */
    private RateLimiterConfig(boolean enabled,
            int permits,
            Duration period,
            Duration maxWait) {
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(maxWait, "maxWait");
        if (permits < 1) {
            throw ErrorCode.invalidValue(POLICY, "permits must be at least 1, was " + permits);
        }
        if (period.isNegative() || period.isZero()) {
            throw ErrorCode.invalidValue(POLICY, "period must be positive, was " + period);
        }
        if (period.compareTo(Duration.ofNanos(permits)) < 0) {
            throw ErrorCode.invalidValue(POLICY, "period must be at least one nanosecond per permit, was " + period
                    + " for " + permits + " permits");
        }
        if (maxWait.isNegative()) {
            throw ErrorCode.invalidValue(POLICY, "maxWait must not be negative, was " + maxWait);
        }
        this.enabled = enabled;
        this.permits = permits;
        this.period = period;
        this.maxWait = maxWait;
    }

    /** Returns a builder that starts from {@link #DEFAULTS}. */
    public static Builder builder() {
        return new Builder(DEFAULTS);
    }

    /** Returns a builder that starts from this configuration. */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /** Mutable builder for {@link RateLimiterConfig}. Values are validated by {@link #build()}. */
    public static final class Builder {
        private boolean enabled;
        private int permits;
        private Duration period;
        private Duration maxWait;

        private Builder(RateLimiterConfig from) {
            enabled = from.enabled;
            permits = from.permits;
            period = from.period;
            maxWait = from.maxWait;
        }

        /** @param enabled whether calls are limited at all @return this builder */
        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        /** @param permits calls allowed per period, and the largest burst @return this builder */
        public Builder permits(int permits) {
            this.permits = permits;
            return this;
        }

        /** @param period length of the period @return this builder */
        public Builder period(Duration period) {
            this.period = Objects.requireNonNull(period, "period");
            return this;
        }

        /** @param maxWait how long a call may wait for a permit; zero rejects immediately @return this builder */
        public Builder maxWait(Duration maxWait) {
            this.maxWait = Objects.requireNonNull(maxWait, "maxWait");
            return this;
        }

        /**
         * Validates the values and builds the configuration.
         *
         * @return the configuration
         * @throws IllegalArgumentException if a value is invalid
         */
        public RateLimiterConfig build() {
            return new RateLimiterConfig(enabled, permits, period, maxWait);
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
     * Calls allowed per {@code period}, and the largest burst; at least 1.
     *
     * @return the value
     */
    public int permits() {
        return permits;
    }

    /**
     * Length of the period; positive, and at least {@code permits} nanoseconds.
     *
     * @return the value
     */
    public Duration period() {
        return period;
    }

    /**
     * How long a call may wait for a permit; {@link Duration#ZERO} rejects immediately.
     *
     * @return the value
     */
    public Duration maxWait() {
        return maxWait;
    }

    /** Equal when every value is equal. */
    @Override
    public boolean equals(Object o) {
        return o instanceof RateLimiterConfig other
                && enabled == other.enabled
                && permits == other.permits
                && period.equals(other.period)
                && maxWait.equals(other.maxWait);
    }

    @Override
    public int hashCode() {
        return Objects.hash(enabled, permits, period, maxWait);
    }

    /** The values, as {@code RateLimiterConfig[name=value, ...]}. */
    @Override
    public String toString() {
        return "RateLimiterConfig[enabled=" + enabled + ", permits=" + permits + ", period=" + period
                + ", maxWait=" + maxWait + "]";
    }

    /** The values in declaration order, for {@link ResilienceSnapshot#toMap()}. */
    java.util.Map<String, Object> attributes() {
        java.util.Map<String, Object> values = new java.util.LinkedHashMap<>();
        values.put("enabled", enabled);
        values.put("permits", permits);
        values.put("period", period);
        values.put("maxWait", maxWait);
        return values;
    }
}
