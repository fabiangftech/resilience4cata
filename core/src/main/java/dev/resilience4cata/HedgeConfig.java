package dev.resilience4cata;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Immutable hedge configuration. Built with {@link #builder()} or {@link #toBuilder()}.
 *
 * <p>A hedge starts the same asynchronous call again when the attempts in flight have produced no result
 * for {@code delay}, or at once when one fails with a failure listed in {@code hedgeOn}, and completes with
 * the first success. Only for idempotent calls.
 *
 * @param enabled whether calls are hedged at all; disabled, the call runs once
 * @param maxAttempts attempts in flight at most, including the first one; between 2 and 10
 * @param delay how long the latest attempt runs without a result before the next one starts; not negative,
 *     {@link Duration#ZERO} starts every attempt at once
 * @param hedgeOn failures after which the next attempt starts at once; a failure not listed ends the call
 * @param abortOn failures that end the call at once and cancel the other attempts; they win over {@code hedgeOn}
 * @param budgetPercent hedge budget: hedges allowed, in percent of the calls made in the last
 *     {@code budgetWindow}, on top of {@code budgetMinHedgesPerSecond}; finite, at least 0; 0 turns the budget off
 * @param budgetMinHedgesPerSecond hedge budget: hedges allowed per second of {@code budgetWindow} whatever
 *     the traffic; at least 0; applies once {@code budgetPercent} turns the budget on
 * @param budgetWindow hedge budget: how far back calls and hedges are counted; between 1 s and 1 h
 * @see #hasBudget()
 */
public record HedgeConfig(
        boolean enabled,
        int maxAttempts,
        Duration delay,
        List<Class<? extends Throwable>> hedgeOn,
        List<Class<? extends Throwable>> abortOn,
        double budgetPercent,
        int budgetMinHedgesPerSecond,
        Duration budgetWindow) {

    /** The fewest attempts: one hedge. */
    public static final int MIN_ATTEMPTS = 2;
    /** The most attempts in flight for one call. */
    public static final int MAX_ATTEMPTS = 10;

    private static final Duration MAX_BUDGET_WINDOW = Duration.ofHours(1);

    /**
     * Built-in defaults: 2 attempts (one hedge), a 1 s delay, hedge on {@link Exception}, empty
     * {@code abortOn}, and a hedge budget of 10 % of the calls plus 1 hedge per second over a 10 s window.
     */
    public static final HedgeConfig DEFAULTS = new HedgeConfig(true, 2, Duration.ofSeconds(1),
            List.of(Exception.class), List.of(), 10.0, 1, Duration.ofSeconds(10));

    /** The policy name in validation messages. */
    private static final String POLICY = "hedge";

    /** Validates every value and copies the exception lists. */
    public HedgeConfig {
        Objects.requireNonNull(delay, "delay");
        Objects.requireNonNull(budgetWindow, "budgetWindow");
        hedgeOn = List.copyOf(hedgeOn);
        abortOn = List.copyOf(abortOn);
        if (maxAttempts < MIN_ATTEMPTS || maxAttempts > MAX_ATTEMPTS) {
            throw ErrorCode.invalidValue(POLICY, "maxAttempts must be between " + MIN_ATTEMPTS + " and " + MAX_ATTEMPTS
                    + ", was " + maxAttempts);
        }
        if (delay.isNegative()) {
            throw ErrorCode.invalidValue(POLICY, "delay must not be negative, was " + delay);
        }
        if (Double.isNaN(budgetPercent) || budgetPercent < 0 || Double.isInfinite(budgetPercent)) {
            throw ErrorCode.invalidValue(POLICY, "budgetPercent must be a finite number >= 0, was " + budgetPercent);
        }
        if (budgetMinHedgesPerSecond < 0) {
            throw ErrorCode.invalidValue(POLICY, "budgetMinHedgesPerSecond must not be negative, was "
                    + budgetMinHedgesPerSecond);
        }
        if (budgetWindow.compareTo(Duration.ofSeconds(1)) < 0 || budgetWindow.compareTo(MAX_BUDGET_WINDOW) > 0) {
            throw ErrorCode.invalidValue(POLICY, "budgetWindow must be between 1s and 1h, was " + budgetWindow);
        }
    }

    /**
     * Returns whether this configuration has a hedge budget: {@code budgetPercent} is above 0 (the default).
     *
     * @return {@code true} if hedges are capped relative to the instance's recent calls
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

    /** Mutable builder for {@link HedgeConfig}. Values are validated by {@link #build()}. */
    public static final class Builder {
        private boolean enabled;
        private int maxAttempts;
        private Duration delay;
        private List<Class<? extends Throwable>> hedgeOn;
        private List<Class<? extends Throwable>> abortOn;
        private double budgetPercent;
        private int budgetMinHedgesPerSecond;
        private Duration budgetWindow;

        private Builder(HedgeConfig from) {
            enabled = from.enabled;
            maxAttempts = from.maxAttempts;
            delay = from.delay;
            hedgeOn = from.hedgeOn;
            abortOn = from.abortOn;
            budgetPercent = from.budgetPercent;
            budgetMinHedgesPerSecond = from.budgetMinHedgesPerSecond;
            budgetWindow = from.budgetWindow;
        }

        /** @param enabled whether calls are hedged at all @return this builder */
        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        /** @param maxAttempts attempts in flight at most, including the first one, 2 to 10 @return this builder */
        public Builder maxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
            return this;
        }

        /** @param delay time without a result before the next attempt starts; zero starts all at once @return this builder */
        public Builder delay(Duration delay) {
            this.delay = Objects.requireNonNull(delay, "delay");
            return this;
        }

        /** @param hedgeOn failures after which the next attempt starts at once @return this builder */
        public Builder hedgeOn(List<Class<? extends Throwable>> hedgeOn) {
            this.hedgeOn = List.copyOf(hedgeOn);
            return this;
        }

        /** @param abortOn failures that end the call at once @return this builder */
        public Builder abortOn(List<Class<? extends Throwable>> abortOn) {
            this.abortOn = List.copyOf(abortOn);
            return this;
        }

        /**
         * @param budgetPercent hedges allowed, in percent of the calls of the last {@code budgetWindow}, on top
         *     of {@code budgetMinHedgesPerSecond}; 0 turns the budget off
         * @return this builder
         */
        public Builder budgetPercent(double budgetPercent) {
            this.budgetPercent = budgetPercent;
            return this;
        }

        /** @param budgetMinHedgesPerSecond hedges allowed per second of {@code budgetWindow} whatever the traffic @return this builder */
        public Builder budgetMinHedgesPerSecond(int budgetMinHedgesPerSecond) {
            this.budgetMinHedgesPerSecond = budgetMinHedgesPerSecond;
            return this;
        }

        /** @param budgetWindow how far back the budget counts calls and hedges @return this builder */
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
        public HedgeConfig build() {
            return new HedgeConfig(enabled, maxAttempts, delay, hedgeOn, abortOn, budgetPercent,
                    budgetMinHedgesPerSecond, budgetWindow);
        }
    }
}
