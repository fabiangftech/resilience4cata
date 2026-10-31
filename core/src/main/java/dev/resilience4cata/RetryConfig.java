package dev.resilience4cata;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Immutable retry configuration. Built with {@link #builder()} or {@link #toBuilder()}.
 *
 * @see #hasBudget()
 */
public final class RetryConfig implements PolicyConfig {

    private final boolean enabled;
    private final int maxAttempts;
    private final Duration delay;
    private final Backoff backoff;
    private final double multiplier;
    private final Duration maxDelay;
    private final Jitter jitter;
    private final Duration maxDuration;
    private final List<Class<? extends Throwable>> retryOn;
    private final List<Class<? extends Throwable>> abortOn;
    private final double budgetPercent;
    private final int budgetMinRetriesPerSecond;
    private final Duration budgetWindow;

    /** The longest {@code budgetWindow}. */
    private static final Duration MAX_BUDGET_WINDOW = Duration.ofHours(1);

    /**
     * Built-in defaults: 3 attempts, exponential backoff from 200 ms up to 10 s, full jitter, retry on
     * {@link Exception}, empty {@code abortOn}. Whatever the configuration, a call rejected by a circuit
     * breaker ({@link CallNotPermittedException}) is never retried: that abort is built in, not an entry
     * of {@code abortOn}. No retry budget; once {@code budgetPercent} turns one on, its reserve is 10 retries per
     * second (as
     * in Finagle's {@code RetryBudget}) over a 10 s {@code budgetWindow}.
     */
    public static final RetryConfig DEFAULTS = new RetryConfig(
            true, 3, Duration.ofMillis(200), Backoff.EXPONENTIAL, 2.0, Duration.ofSeconds(10),
            Jitter.FULL, Duration.ZERO, List.of(Exception.class), List.of(), 0.0, 10, Duration.ofSeconds(10));


    /**
     * How the delay grows between attempts. Constants may be added in minor versions: keep a {@code default} branch
     * in a {@code switch} over it.
     */
    public enum Backoff {
        /** The same delay every time. */
        FIXED,
        /** The delay is multiplied by {@code multiplier} after each attempt, up to {@code maxDelay}. */
        EXPONENTIAL
    }

    /**
     * Randomisation applied to each delay, so that clients do not retry in lockstep. Constants may be added in
     * minor versions: keep a {@code default} branch in a {@code switch} over it.
     */
    public enum Jitter {
        /** No randomisation. */
        NONE,
        /** A random delay between zero and the computed delay. */
        FULL,
        /** Half of the computed delay, plus a random part of the other half. */
        EQUAL
    }

    /** The policy name in validation messages. */
    private static final String POLICY = "retry";

    /** Validates every value and copies the lists. */
    private RetryConfig(boolean enabled,
            int maxAttempts,
            Duration delay,
            Backoff backoff,
            double multiplier,
            Duration maxDelay,
            Jitter jitter,
            Duration maxDuration,
            List<Class<? extends Throwable>> retryOn,
            List<Class<? extends Throwable>> abortOn,
            double budgetPercent,
            int budgetMinRetriesPerSecond,
            Duration budgetWindow) {
        Objects.requireNonNull(delay, "delay");
        Objects.requireNonNull(backoff, "backoff");
        Objects.requireNonNull(maxDelay, "maxDelay");
        Objects.requireNonNull(jitter, "jitter");
        Objects.requireNonNull(maxDuration, "maxDuration");
        Objects.requireNonNull(budgetWindow, "budgetWindow");
        retryOn = List.copyOf(retryOn);
        abortOn = List.copyOf(abortOn);
        if (maxAttempts < 1) {
            throw ErrorCode.invalidValue(POLICY, "maxAttempts must be at least 1, was " + maxAttempts);
        }
        if (delay.isNegative()) {
            throw ErrorCode.invalidValue(POLICY, "delay must not be negative, was " + delay);
        }
        if (Double.isNaN(multiplier) || multiplier < 1.0 || Double.isInfinite(multiplier)) {
            throw ErrorCode.invalidValue(POLICY, "multiplier must be a finite number >= 1, was " + multiplier);
        }
        if (maxDelay.compareTo(delay) < 0) {
            throw ErrorCode.invalidValue(POLICY, "maxDelay (" + maxDelay + ") must not be shorter than delay (" + delay + ")");
        }
        if (maxDuration.isNegative()) {
            throw ErrorCode.invalidValue(POLICY, "maxDuration must not be negative, was " + maxDuration);
        }
        if (Double.isNaN(budgetPercent) || budgetPercent < 0 || Double.isInfinite(budgetPercent)) {
            throw ErrorCode.invalidValue(POLICY, "budgetPercent must be a finite number >= 0, was " + budgetPercent);
        }
        if (budgetMinRetriesPerSecond < 0) {
            throw ErrorCode.invalidValue(POLICY, "budgetMinRetriesPerSecond must not be negative, was "
                    + budgetMinRetriesPerSecond);
        }
        if (budgetWindow.compareTo(Duration.ofSeconds(1)) < 0 || budgetWindow.compareTo(MAX_BUDGET_WINDOW) > 0) {
            throw ErrorCode.invalidValue(POLICY, "budgetWindow must be between 1s and 1h, was " + budgetWindow);
        }
        this.enabled = enabled;
        this.maxAttempts = maxAttempts;
        this.delay = delay;
        this.backoff = backoff;
        this.multiplier = multiplier;
        this.maxDelay = maxDelay;
        this.jitter = jitter;
        this.maxDuration = maxDuration;
        this.retryOn = retryOn;
        this.abortOn = abortOn;
        this.budgetPercent = budgetPercent;
        this.budgetMinRetriesPerSecond = budgetMinRetriesPerSecond;
        this.budgetWindow = budgetWindow;
    }

    /**
     * Returns whether this configuration has a retry budget: {@code budgetPercent} is above 0. The reserve
     * ({@code budgetMinRetriesPerSecond}, 10 by default) only applies to a budget that is on. Without one, the
     * retry keeps no state between calls.
     *
     * @return {@code true} if retries are capped relative to the instance's recent calls
     */
    public boolean hasBudget() {
        return budgetPercent > 0;
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
        private double budgetPercent;
        private int budgetMinRetriesPerSecond;
        private Duration budgetWindow;

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
            budgetPercent = from.budgetPercent;
            budgetMinRetriesPerSecond = from.budgetMinRetriesPerSecond;
            budgetWindow = from.budgetWindow;
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
         * @param budgetPercent retries allowed, in percent of the calls of the last {@code budgetWindow}, on
         *     top of {@code budgetMinRetriesPerSecond}; above 0 turns the budget on, 0 turns it off
         * @return this builder
         */
        public Builder budgetPercent(double budgetPercent) {
            this.budgetPercent = budgetPercent;
            return this;
        }

        /**
         * @param budgetMinRetriesPerSecond retries allowed per second of {@code budgetWindow} whatever the
         *     traffic, once {@code budgetPercent} turns the budget on (default 10; 0 for a percentage alone)
         * @return this builder
         */
        public Builder budgetMinRetriesPerSecond(int budgetMinRetriesPerSecond) {
            this.budgetMinRetriesPerSecond = budgetMinRetriesPerSecond;
            return this;
        }

        /** @param budgetWindow how far back the budget counts calls and retries @return this builder */
        public Builder budgetWindow(Duration budgetWindow) {
            this.budgetWindow = Objects.requireNonNull(budgetWindow, "budgetWindow");
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
                    maxDuration, retryOn, abortOn, budgetPercent, budgetMinRetriesPerSecond, budgetWindow);
        }
    }

    /**
     * Whether failed calls are retried at all.
     *
     * @return the value
     */
    @Override
    public boolean enabled() {
        return enabled;
    }

    /**
     * Total attempts, including the first one; at least 1.
     *
     * @return the value
     */
    public int maxAttempts() {
        return maxAttempts;
    }

    /**
     * Delay before the first retry.
     *
     * @return the value
     */
    public Duration delay() {
        return delay;
    }

    /**
     * How the delay grows between attempts.
     *
     * @return the value
     */
    public Backoff backoff() {
        return backoff;
    }

    /**
     * Growth factor for {@link Backoff#EXPONENTIAL}; at least 1.
     *
     * @return the value
     */
    public double multiplier() {
        return multiplier;
    }

    /**
     * Upper bound for a single delay; at least {@code delay}.
     *
     * @return the value
     */
    public Duration maxDelay() {
        return maxDelay;
    }

    /**
     * Randomisation applied to each delay.
     *
     * @return the value
     */
    public Jitter jitter() {
        return jitter;
    }

    /**
     * Time budget for the whole operation; {@link Duration#ZERO} means unlimited.
     *
     * @return the value
     */
    public Duration maxDuration() {
        return maxDuration;
    }

    /**
     * Exceptions that trigger a retry.
     *
     * @return the value
     */
    public List<Class<? extends Throwable>> retryOn() {
        return retryOn;
    }

    /**
     * Exceptions that stop retrying; they win over {@code retryOn}. A {@link CallNotPermittedException} always stops
     * retrying, whether listed or not.
     *
     * @return the value
     */
    public List<Class<? extends Throwable>> abortOn() {
        return abortOn;
    }

    /**
     * Retry budget: retries allowed, in percent of the calls made in the last {@code budgetWindow}, on top of {@code
     * budgetMinRetriesPerSecond}; finite, at least 0. Above 0 it turns the budget on; 0 (the default) means no budget.
     *
     * @return the value
     */
    public double budgetPercent() {
        return budgetPercent;
    }

    /**
     * Retry budget: retries allowed per second of {@code budgetWindow} whatever the traffic, so that rarely called
     * methods can still retry; applies once {@code budgetPercent} turns the budget on; at least 0 (default 10).
     *
     * @return the value
     */
    public int budgetMinRetriesPerSecond() {
        return budgetMinRetriesPerSecond;
    }

    /**
     * Retry budget: how far back calls and retries are counted; between 1 s and 1 h.
     *
     * @return the value
     */
    public Duration budgetWindow() {
        return budgetWindow;
    }

    /** Equal when every value is equal. */
    @Override
    public boolean equals(Object o) {
        return o instanceof RetryConfig other
                && enabled == other.enabled
                && maxAttempts == other.maxAttempts
                && delay.equals(other.delay)
                && backoff.equals(other.backoff)
                && Double.compare(multiplier, other.multiplier) == 0
                && maxDelay.equals(other.maxDelay)
                && jitter.equals(other.jitter)
                && maxDuration.equals(other.maxDuration)
                && retryOn.equals(other.retryOn)
                && abortOn.equals(other.abortOn)
                && Double.compare(budgetPercent, other.budgetPercent) == 0
                && budgetMinRetriesPerSecond == other.budgetMinRetriesPerSecond
                && budgetWindow.equals(other.budgetWindow);
    }

    @Override
    public int hashCode() {
        return Objects.hash(enabled, maxAttempts, delay, backoff, multiplier, maxDelay, jitter, maxDuration, retryOn,
                abortOn, budgetPercent, budgetMinRetriesPerSecond, budgetWindow);
    }

    /** The values, as {@code RetryConfig[name=value, ...]}. */
    @Override
    public String toString() {
        return "RetryConfig[enabled=" + enabled + ", maxAttempts=" + maxAttempts + ", delay=" + delay
                + ", backoff=" + backoff + ", multiplier=" + multiplier + ", maxDelay=" + maxDelay
                + ", jitter=" + jitter + ", maxDuration=" + maxDuration + ", retryOn=" + retryOn
                + ", abortOn=" + abortOn + ", budgetPercent=" + budgetPercent
                + ", budgetMinRetriesPerSecond=" + budgetMinRetriesPerSecond + ", budgetWindow=" + budgetWindow + "]";
    }

    /** The values in declaration order, for {@link ResilienceSnapshot#toMap()}. */
    java.util.Map<String, Object> attributes() {
        java.util.Map<String, Object> values = new java.util.LinkedHashMap<>();
        values.put("enabled", enabled);
        values.put("maxAttempts", maxAttempts);
        values.put("delay", delay);
        values.put("backoff", backoff);
        values.put("multiplier", multiplier);
        values.put("maxDelay", maxDelay);
        values.put("jitter", jitter);
        values.put("maxDuration", maxDuration);
        values.put("retryOn", retryOn);
        values.put("abortOn", abortOn);
        values.put("budgetPercent", budgetPercent);
        values.put("budgetMinRetriesPerSecond", budgetMinRetriesPerSecond);
        values.put("budgetWindow", budgetWindow);
        return values;
    }
}
