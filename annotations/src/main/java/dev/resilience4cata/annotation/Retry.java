package dev.resilience4cata.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Calls the method again when it fails. */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface Retry {

    /** Stateful instance id. Empty means one instance per method. */
    String name() default "";

    /** Named configuration to start from. Empty means {@code default}. */
    String config() default "";

    /** Total attempts, including the first one. */
    int maxAttempts() default -1;

    /** Initial delay between attempts, for example {@code "200ms"}. */
    String delay() default "";

    /** How the delay grows between attempts. */
    Backoff backoff() default Backoff.INHERIT;

    /** Growth factor for {@link Backoff#EXPONENTIAL}. */
    double multiplier() default -1;

    /** Upper bound for a single delay. */
    String maxDelay() default "";

    /** Randomisation applied to each delay. */
    Jitter jitter() default Jitter.INHERIT;

    /** Time budget for the whole operation, across all attempts. */
    String maxDuration() default "";

    /** Exceptions that trigger a retry. Empty means inherit. */
    Class<? extends Throwable>[] retryOn() default {};

    /** Exceptions that stop retrying immediately. */
    Class<? extends Throwable>[] abortOn() default {};

    /**
     * Classifier for results and exceptions. Must implement {@code dev.resilience4cata.Classifier};
     * the annotation processor checks it. {@code void.class} means inherit.
     */
    Class<?> classifier() default void.class;

    /** Tri-state switch: {@code ""}, {@code "true"} or {@code "false"}. */
    String enabled() default "";
}
