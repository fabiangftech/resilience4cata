package dev.resilience4cata.annotation;

/** How the delay grows between retry attempts. */
public enum Backoff {
    /** Unset: use the value from configuration. */
    INHERIT,
    /** The same delay every time. */
    FIXED,
    /** The delay is multiplied by {@code multiplier} after each attempt. */
    EXPONENTIAL
}
