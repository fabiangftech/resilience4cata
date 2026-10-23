package dev.resilience4cata.annotation;

/** How a circuit breaker's sliding window is measured. */
public enum WindowType {
    /** Unset: use the value from configuration. */
    INHERIT,
    /** The last N calls. */
    COUNT,
    /** The calls of the last N seconds. */
    TIME
}
