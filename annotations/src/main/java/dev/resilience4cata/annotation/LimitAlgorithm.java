package dev.resilience4cata.annotation;

/** How an adaptive limit recomputes its limit at the end of each window. */
public enum LimitAlgorithm {
    /** Unset: use the value from configuration. */
    INHERIT,
    /**
     * Compares each window's latency with a long-term average (Netflix's Gradient2): grows while latency
     * holds, shrinks when it rises. The built-in default.
     */
    GRADIENT2,
    /** Additive increase, multiplicative decrease: grows by one, backs off after a drop (a timeout, a full bulkhead). */
    AIMD
}
