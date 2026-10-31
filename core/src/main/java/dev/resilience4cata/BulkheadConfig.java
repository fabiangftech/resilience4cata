package dev.resilience4cata;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable bulkhead configuration. Built with {@link #builder()} or {@link #toBuilder()}.
 */
public final class BulkheadConfig implements PolicyConfig {

    private final boolean enabled;
    private final int maxConcurrent;
    private final Duration maxWait;

    /** Built-in defaults, the same as Resilience4j's: 25 concurrent calls, no waiting. */
    public static final BulkheadConfig DEFAULTS = new BulkheadConfig(true, 25, Duration.ZERO);

    /** Validates every value. */
    private BulkheadConfig(boolean enabled, int maxConcurrent, Duration maxWait) {
        Objects.requireNonNull(maxWait, "maxWait");
        if (maxConcurrent < 1) {
            throw ErrorCode.invalidValue("bulkhead", "maxConcurrent must be at least 1, was " + maxConcurrent);
        }
        if (maxWait.isNegative()) {
            throw ErrorCode.invalidValue("bulkhead", "maxWait must not be negative, was " + maxWait);
        }
        this.enabled = enabled;
        this.maxConcurrent = maxConcurrent;
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

    /** Mutable builder for {@link BulkheadConfig}. Values are validated by {@link #build()}. */
    public static final class Builder {
        private boolean enabled;
        private int maxConcurrent;
        private Duration maxWait;

        private Builder(BulkheadConfig from) {
            enabled = from.enabled;
            maxConcurrent = from.maxConcurrent;
            maxWait = from.maxWait;
        }

        /** @param enabled whether concurrent calls are limited at all @return this builder */
        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        /** @param maxConcurrent maximum number of concurrent calls @return this builder */
        public Builder maxConcurrent(int maxConcurrent) {
            this.maxConcurrent = maxConcurrent;
            return this;
        }

        /** @param maxWait how long a call may wait for a slot; zero rejects immediately @return this builder */
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
        public BulkheadConfig build() {
            return new BulkheadConfig(enabled, maxConcurrent, maxWait);
        }
    }

    /**
     * Whether the number of concurrent calls is limited at all.
     *
     * @return the value
     */
    @Override
    public boolean enabled() {
        return enabled;
    }

    /**
     * Maximum number of calls running at the same time; at least 1.
     *
     * @return the value
     */
    public int maxConcurrent() {
        return maxConcurrent;
    }

    /**
     * How long a call may wait for a free slot; {@link Duration#ZERO} rejects immediately.
     *
     * @return the value
     */
    public Duration maxWait() {
        return maxWait;
    }

    /** Equal when every value is equal. */
    @Override
    public boolean equals(Object o) {
        return o instanceof BulkheadConfig other
                && enabled == other.enabled
                && maxConcurrent == other.maxConcurrent
                && maxWait.equals(other.maxWait);
    }

    @Override
    public int hashCode() {
        return Objects.hash(enabled, maxConcurrent, maxWait);
    }

    /** The values, as {@code BulkheadConfig[name=value, ...]}. */
    @Override
    public String toString() {
        return "BulkheadConfig[enabled=" + enabled + ", maxConcurrent=" + maxConcurrent + ", maxWait=" + maxWait + "]";
    }

    /** The values in declaration order, for {@link ResilienceSnapshot#toMap()}. */
    java.util.Map<String, Object> attributes() {
        java.util.Map<String, Object> values = new java.util.LinkedHashMap<>();
        values.put("enabled", enabled);
        values.put("maxConcurrent", maxConcurrent);
        values.put("maxWait", maxWait);
        return values;
    }
}
