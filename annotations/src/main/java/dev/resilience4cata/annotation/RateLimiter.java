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

    /** Calls allowed per {@link #period()}. */
    int permits() default -1;

    /** Length of the period, for example {@code "1s"}. */
    String period() default "";

    /** How long a call may wait for a permit. {@code "0"} means reject immediately. */
    String maxWait() default "";

    /** Tri-state switch: {@code ""}, {@code "true"} or {@code "false"}. */
    String enabled() default "";
}
