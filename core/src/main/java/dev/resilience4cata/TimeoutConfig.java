package dev.resilience4cata;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable timeout configuration. Built with {@link #builder()} or {@link #toBuilder()}.
 *
 * @param enabled whether calls are timed out at all
 * @param timeout how long one attempt may take; must be positive
 */
public record TimeoutConfig(boolean enabled, Duration timeout) {

    /** Built-in defaults: 1 second, as in Resilience4j and MicroProfile Fault Tolerance. */
    public static final TimeoutConfig DEFAULTS = new TimeoutConfig(true, Duration.ofSeconds(1));

    /** The policy name in validation messages. */
    private static final String POLICY = "timeout";

    /** Validates the values. */
    public TimeoutConfig {
        Objects.requireNonNull(timeout, POLICY); // the component has the policy's name
        if (timeout.isNegative() || timeout.isZero()) {
            throw ErrorCode.invalidValue(POLICY, "timeout must be positive, was " + timeout);
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

    /** Mutable builder for {@link TimeoutConfig}. Values are validated by {@link #build()}. */
    public static final class Builder {
        private boolean enabled;
        private Duration timeout;

        private Builder(TimeoutConfig from) {
            enabled = from.enabled;
            timeout = from.timeout;
        }

        /** @param enabled whether calls are timed out at all @return this builder */
        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        /** @param timeout how long one attempt may take @return this builder */
        public Builder timeout(Duration timeout) {
            this.timeout = Objects.requireNonNull(timeout, POLICY);
            return this;
        }

        /**
         * Validates the values and builds the configuration.
         *
         * @return the configuration
         * @throws IllegalArgumentException if a value is invalid
         */
        public TimeoutConfig build() {
            return new TimeoutConfig(enabled, timeout);
        }
    }
}
