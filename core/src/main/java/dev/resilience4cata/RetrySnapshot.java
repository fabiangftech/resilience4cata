package dev.resilience4cata;

import org.jspecify.annotations.Nullable;

/**
 * One retry instance. Without a retry budget, retries keep no state between calls, so only the
 * configuration is shown.
 *
 * @param instance the retry's id
 * @param config its resolved configuration
 * @param budget the retry budget's current window; {@code null} without a budget ({@link RetryConfig#hasBudget()})
 */
public record RetrySnapshot(String instance, RetryConfig config, @Nullable Budget budget) {

    /**
     * A snapshot without a retry budget: the constructor of 0.2.0, kept so that code that calls it still compiles.
     *
     * @param instance the retry's id
     * @param config its resolved configuration
     */
    public RetrySnapshot(String instance, RetryConfig config) {
        this(instance, config, null);
    }

    /**
     * The window of a retry budget, read when the snapshot was taken. The three counts are read one after
     * the other while calls go on, so they are approximate.
     *
     * @param calls calls counted in the window
     * @param retries retries granted in the window
     * @param allowedRetries retries the budget allows in the window with these calls; once {@code retries}
     *     reaches it, failures are not retried
     */
    public record Budget(long calls, long retries, long allowedRetries) {
    }
}
