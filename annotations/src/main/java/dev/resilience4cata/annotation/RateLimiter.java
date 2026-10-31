package dev.resilience4cata.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Limits how many calls are allowed per period. */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RateLimiter {

    /** Stateful instance id. Empty means one instance per method. */
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

    /** Calls allowed per {@link #period()}. */
    int permits() default -1;

    /** Length of the period, for example {@code "1s"}. */
    String period() default "";

    /** How long a call may wait for a permit. {@code "0"} means reject immediately. */
    String maxWait() default "";

    /**
     * Whether the policy acts at all; {@code false} lets every call through. Not set means inherit (the built-in
     * default is {@code true}).
     */
    boolean enabled() default true;
}
