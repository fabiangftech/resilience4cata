package dev.resilience4cata;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable bulkhead configuration. Built with {@link #builder()} or {@link #toBuilder()}.
 *
 * @param enabled whether the number of concurrent calls is limited at all
 * @param maxConcurrent maximum number of calls running at the same time; at least 1
 * @param maxWait how long a call may wait for a free slot; {@link Duration#ZERO} rejects immediately
 */
public record BulkheadConfig(boolean enabled, int maxConcurrent, Duration maxWait) {

    /** Built-in defaults, the same as Resilience4j's: 25 concurrent calls, no waiting. */
    public static final BulkheadConfig DEFAULTS = new BulkheadConfig(true, 25, Duration.ZERO);

    /** Validates the values. */
    public BulkheadConfig {
        Objects.requireNonNull(maxWait, "maxWait");
        if (maxConcurrent < 1) {
            throw ErrorCode.invalidValue("bulkhead", "maxConcurrent must be at least 1, was " + maxConcurrent);
        }
        if (maxWait.isNegative()) {
            throw ErrorCode.invalidValue("bulkhead", "maxWait must not be negative, was " + maxWait);
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
}
