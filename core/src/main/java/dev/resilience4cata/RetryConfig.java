package dev.resilience4cata;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Immutable retry configuration. Built with {@link #builder()} or {@link #toBuilder()}.
 *
 * @param enabled whether failed calls are retried at all
 * @param maxAttempts total attempts, including the first one; at least 1
 * @param delay delay before the first retry
 * @param backoff how the delay grows between attempts
 * @param multiplier growth factor for {@link Backoff#EXPONENTIAL}; at least 1
 * @param maxDelay upper bound for a single delay; at least {@code delay}
 * @param jitter randomisation applied to each delay
 * @param maxDuration time budget for the whole operation; {@link Duration#ZERO} means unlimited
 * @param retryOn exceptions that trigger a retry
 * @param abortOn exceptions that stop retrying; they win over {@code retryOn}. A
 *     {@link CallNotPermittedException} always stops retrying, whether listed or not
 */
public record RetryConfig(
        boolean enabled,
        int maxAttempts,
        Duration delay,
        Backoff backoff,
        double multiplier,
        Duration maxDelay,
        Jitter jitter,
        Duration maxDuration,
        List<Class<? extends Throwable>> retryOn,
        List<Class<? extends Throwable>> abortOn) {

    /**
     * Built-in defaults: 3 attempts, exponential backoff from 200 ms up to 10 s, full jitter, retry on
     * {@link Exception}, empty {@code abortOn}. Whatever the configuration, a call rejected by a circuit
     * breaker ({@link CallNotPermittedException}) is never retried: that abort is built in, not an entry
     * of {@code abortOn}.
     */
    public static final RetryConfig DEFAULTS = new RetryConfig(
            true, 3, Duration.ofMillis(200), Backoff.EXPONENTIAL, 2.0, Duration.ofSeconds(10),
            Jitter.FULL, Duration.ZERO, List.of(Exception.class), List.of());

    /** How the delay grows between attempts. */
    public enum Backoff {
        /** The same delay every time. */
        FIXED,
        /** The delay is multiplied by {@code multiplier} after each attempt, up to {@code maxDelay}. */
        EXPONENTIAL
    }

    /** Randomisation applied to each delay, so that clients do not retry in lockstep. */
    public enum Jitter {
        /** No randomisation. */
        NONE,
        /** A random delay between zero and the computed delay. */
        FULL,
        /** Half of the computed delay, plus a random part of the other half. */
        EQUAL
    }

    /** Validates every value and copies the exception lists. */
    public RetryConfig {
        Objects.requireNonNull(delay, "delay");
        Objects.requireNonNull(backoff, "backoff");
        Objects.requireNonNull(maxDelay, "maxDelay");
        Objects.requireNonNull(jitter, "jitter");
        Objects.requireNonNull(maxDuration, "maxDuration");
        retryOn = List.copyOf(retryOn);
        abortOn = List.copyOf(abortOn);
        if (maxAttempts < 1) {
            throw ErrorCode.invalidValue("retry", "maxAttempts must be at least 1, was " + maxAttempts);
        }
        if (delay.isNegative()) {
            throw ErrorCode.invalidValue("retry", "delay must not be negative, was " + delay);
        }
        if (!(multiplier >= 1.0) || Double.isInfinite(multiplier)) {
            throw ErrorCode.invalidValue("retry", "multiplier must be a finite number >= 1, was " + multiplier);
        }
        if (maxDelay.compareTo(delay) < 0) {
            throw ErrorCode.invalidValue("retry", "maxDelay (" + maxDelay + ") must not be shorter than delay (" + delay + ")");
        }
        if (maxDuration.isNegative()) {
            throw ErrorCode.invalidValue("retry", "maxDuration must not be negative, was " + maxDuration);
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

    /** Mutable builder for {@link RetryConfig}. Values are validated by {@link #build()}. */
    public static final class Builder {
        private boolean enabled;
        private int maxAttempts;
        private Duration delay;
        private Backoff backoff;
        private double multiplier;
        private Duration maxDelay;
        private Jitter jitter;
        private Duration maxDuration;
        private List<Class<? extends Throwable>> retryOn;
        private List<Class<? extends Throwable>> abortOn;

        private Builder(RetryConfig from) {
            enabled = from.enabled;
            maxAttempts = from.maxAttempts;
            delay = from.delay;
            backoff = from.backoff;
            multiplier = from.multiplier;
            maxDelay = from.maxDelay;
            jitter = from.jitter;
            maxDuration = from.maxDuration;
            retryOn = from.retryOn;
            abortOn = from.abortOn;
        }

        /** @param enabled whether failed calls are retried at all @return this builder */
        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        /** @param maxAttempts total attempts, including the first one @return this builder */
        public Builder maxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
            return this;
        }

        /** @param delay delay before the first retry @return this builder */
        public Builder delay(Duration delay) {
            this.delay = Objects.requireNonNull(delay, "delay");
            return this;
        }

        /** @param backoff how the delay grows between attempts @return this builder */
        public Builder backoff(Backoff backoff) {
            this.backoff = Objects.requireNonNull(backoff, "backoff");
            return this;
        }

        /** @param multiplier growth factor for exponential backoff @return this builder */
        public Builder multiplier(double multiplier) {
            this.multiplier = multiplier;
            return this;
        }

        /** @param maxDelay upper bound for a single delay @return this builder */
        public Builder maxDelay(Duration maxDelay) {
            this.maxDelay = Objects.requireNonNull(maxDelay, "maxDelay");
            return this;
        }

        /** @param jitter randomisation applied to each delay @return this builder */
        public Builder jitter(Jitter jitter) {
            this.jitter = Objects.requireNonNull(jitter, "jitter");
            return this;
        }

        /** @param maxDuration time budget for the whole operation; zero means unlimited @return this builder */
        public Builder maxDuration(Duration maxDuration) {
            this.maxDuration = Objects.requireNonNull(maxDuration, "maxDuration");
            return this;
        }

        /** @param retryOn exceptions that trigger a retry @return this builder */
        public Builder retryOn(List<Class<? extends Throwable>> retryOn) {
            this.retryOn = List.copyOf(retryOn);
            return this;
        }

        /** @param abortOn exceptions that stop retrying @return this builder */
        public Builder abortOn(List<Class<? extends Throwable>> abortOn) {
            this.abortOn = List.copyOf(abortOn);
            return this;
        }

        /**
         * Validates the values and builds the configuration.
         *
         * @return the configuration
         * @throws IllegalArgumentException if a value is invalid
         */
        public RetryConfig build() {
            return new RetryConfig(enabled, maxAttempts, delay, backoff, multiplier, maxDelay, jitter,
                    maxDuration, retryOn, abortOn);
        }
    }
}
