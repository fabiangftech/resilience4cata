package dev.resilience4cata;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable timeout configuration. Built with {@link #builder()} or {@link #toBuilder()}.
 */
public final class TimeoutConfig implements PolicyConfig {

    private final boolean enabled;
    private final Duration timeout;

    /** Built-in defaults: 1 second, as in Resilience4j and MicroProfile Fault Tolerance. */
    public static final TimeoutConfig DEFAULTS = new TimeoutConfig(true, Duration.ofSeconds(1));

    /** The policy name in validation messages. */
    private static final String POLICY = "timeout";

    /** Validates every value. */
    private TimeoutConfig(boolean enabled, Duration timeout) {
        Objects.requireNonNull(timeout, POLICY); // the component has the policy's name
        if (timeout.isNegative() || timeout.isZero()) {
            throw ErrorCode.invalidValue(POLICY, "timeout must be positive, was " + timeout);
        }
        this.enabled = enabled;
        this.timeout = timeout;
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

    /**
     * Whether calls are timed out at all.
     *
     * @return the value
     */
    @Override
    public boolean enabled() {
        return enabled;
    }

    /**
     * How long one attempt may take; must be positive.
     *
     * @return the value
     */
    public Duration timeout() {
        return timeout;
    }

    /** Equal when every value is equal. */
    @Override
    public boolean equals(Object o) {
        return o instanceof TimeoutConfig other
                && enabled == other.enabled
                && timeout.equals(other.timeout);
    }

    @Override
    public int hashCode() {
        return Objects.hash(enabled, timeout);
    }

    /** The values, as {@code TimeoutConfig[name=value, ...]}. */
    @Override
    public String toString() {
        return "TimeoutConfig[enabled=" + enabled + ", timeout=" + timeout + "]";
    }

    /** The values in declaration order, for {@link ResilienceSnapshot#toMap()}. */
    java.util.Map<String, Object> attributes() {
        java.util.Map<String, Object> values = new java.util.LinkedHashMap<>();
        values.put("enabled", enabled);
        values.put("timeout", timeout);
        return values;
    }
}
