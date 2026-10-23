package dev.resilience4cata.annotation;

/** Randomisation applied to retry delays, so that clients do not retry in lockstep. */
public enum Jitter {
    /** Unset: use the value from configuration. */
    INHERIT,
    /** No randomisation. */
    NONE,
    /** A random delay between zero and the computed delay. */
    FULL,
    /** Half of the computed delay, plus a random part of the other half. */
    EQUAL
}
