package dev.resilience4cata.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Limits how many calls run at the same time, like {@link Bulkhead}, with a limit that adjusts itself from
 * the latency of the calls: it grows while latency holds and shrinks when latency rises or calls are dropped
 * (a timeout, a full bulkhead inside). A full limit rejects the call at once with
 * {@code LimitExceededException}.
 *
 * <p>The algorithms' own tuning (Gradient2's smoothing, tolerance, long window and queue size; AIMD's
 * backoff ratio) is set in a named configuration or in external configuration, not here.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface AdaptiveLimit {

    /** Stateful instance id: methods with the same name share one limit. Empty means one instance per method. */
    String name() default "";

    /** Named configuration to start from. Empty means {@code default}. */
    String config() default "";

    /**
     * Locks the values set in this annotation (directly, at type level or through a bundle): per-instance
     * overrides ({@code instances.<id>.*} in external configuration, or {@code *Instance(...)} on the registry
     * builder) cannot change them, and an override that tries to is ignored and logged. Attributes the
     * annotation leaves unset can still be overridden. Named configurations never override an annotation.
     */
    boolean locked() default false;

    /** How the limit is recomputed. {@code INHERIT} means inherit (the built-in default is {@code GRADIENT2}). */
    LimitAlgorithm algorithm() default LimitAlgorithm.INHERIT;

    /** The limit before the first window ends. {@code -1} means inherit (the built-in default is 20). */
    int initialLimit() default -1;

    /** The lowest the limit goes, at least 1. {@code -1} means inherit (the built-in default is 1). */
    int minLimit() default -1;

    /** The highest the limit goes. {@code -1} means inherit (the built-in default is 200). */
    int maxLimit() default -1;

    /**
     * How long a window lasts at least, between {@code "10ms"} and {@code "1h"}; the limit is recomputed at the
     * end of each one. Empty means inherit (the built-in default is {@code "1s"}).
     */
    String window() default "";

    /**
     * Failures that mean the dependency is overloaded: they make the limit back off. Any other failure frees
     * the slot and is not counted. Empty means inherit (the built-in default is {@code CallTimeoutException},
     * {@code BulkheadFullException} and {@code java.util.concurrent.TimeoutException}).
     */
    Class<? extends Throwable>[] dropOn() default {};

    /** Failures that are never counted, even if listed in {@link #dropOn()}. Empty means inherit (none). */
    Class<? extends Throwable>[] ignoreOn() default {};

    /** Tri-state switch: {@code ""}, {@code "true"} or {@code "false"}. */
    String enabled() default "";
}
