package dev.resilience4cata.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Limits how many calls run at the same time. */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface Bulkhead {

    /** Stateful instance id. Empty means one instance per method. */
    String name() default "";

    /** Named configuration to start from. Empty means {@code default}. */
    String config() default "";

    /** Maximum number of concurrent calls. */
    int maxConcurrent() default -1;

    /** How long a call may wait for a slot. {@code "0"} means reject immediately. */
    String maxWait() default "";

    /** Tri-state switch: {@code ""}, {@code "true"} or {@code "false"}. */
    String enabled() default "";
}
