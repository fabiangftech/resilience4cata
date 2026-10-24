package dev.resilience4cata;

/**
 * One retry instance. Retries keep no state between calls, so only the configuration is shown.
 *
 * @param instance the retry's id
 * @param config its resolved configuration
 */
public record RetrySnapshot(String instance, RetryConfig config) {
}
