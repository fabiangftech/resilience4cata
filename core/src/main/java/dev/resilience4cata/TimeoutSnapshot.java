package dev.resilience4cata;

/**
 * One timeout instance. Timeouts keep no state between calls, so only the configuration is shown.
 *
 * @param id the timeout's id
 * @param config its resolved configuration
 */
public record TimeoutSnapshot(String id, TimeoutConfig config) {
}
