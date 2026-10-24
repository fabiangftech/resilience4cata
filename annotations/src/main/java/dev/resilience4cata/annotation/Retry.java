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
     * Classifier for results and exceptions: a class implementing {@code dev.resilience4cata.Classifier<T>},
     * where the method's return type (or {@code T} of its {@code CompletionStage<T>}) is assignable to
     * {@code T}, with a public no-argument constructor. One instance is created per method. The
     * annotation processor checks it. {@code void.class} means none.
     *
     * <p>With a classifier, an attempt is retried only if the classifier returns {@code FAILURE}, for a
     * result or an exception. It replaces {@code retryOn} (setting both is a compile error); {@code abortOn}
     * still wins over it. When the retries give up on a {@code FAILURE} result, that last result is
     * returned (no {@code @Fallback}, which only handles exceptions). {@code SUCCESS} and {@code IGNORE}
     * end the loop. An exception thrown by the classifier ends the retries as the call's failure.
     */
    Class<?> classifier() default void.class;

    /** Tri-state switch: {@code ""}, {@code "true"} or {@code "false"}. */
    String enabled() default "";
}
