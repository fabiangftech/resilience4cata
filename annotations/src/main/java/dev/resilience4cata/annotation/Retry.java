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

    /**
     * Locks the values set in this annotation (directly, at type level or through a bundle): per-instance
     * overrides ({@code instances.<id>.*} in external configuration, or {@code *Instance(...)} on the registry
     * builder) cannot change them, and an override that tries to is ignored and logged. Attributes the
     * annotation leaves unset can still be overridden. Named configurations never override an annotation.
     */
    boolean locked() default false;

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

    /**
     * Exceptions that stop retrying immediately; they win over {@code retryOn} and the classifier. Empty
     * means inherit (the built-in default is empty). Whatever this lists, a call rejected by a circuit
     * breaker ({@code dev.resilience4cata.CallNotPermittedException}) is never retried.
     */
    Class<? extends Throwable>[] abortOn() default {};

    /**
     * Classifier for results and exceptions: a class implementing {@code dev.resilience4cata.Classifier<T>},
     * where the method's return type (or {@code T} of its {@code CompletionStage<T>}) is assignable to
     * {@code T}, with a public no-argument constructor. One instance is created per method. The
     * annotation processor checks it. {@code void.class} means none.
     *
     * <p>With a classifier, an attempt is retried only if the classifier returns {@code FAILURE}, for a
     * result or an exception. It replaces {@code retryOn} (setting both is a compile error); {@code abortOn}
     * still wins over it, and a {@code CallNotPermittedException} is never retried. When the retries give up on a {@code FAILURE} result, that last result is
     * returned (no {@code @Fallback}, which only handles exceptions), or thrown inside a
     * {@code ResultRejectedException} with {@link #onExhaustedResult()} {@code = THROW}. {@code SUCCESS}
     * and {@code IGNORE} end the loop. An exception thrown by the classifier ends the retries as the
     * call's failure.
     */
    Class<?> classifier() default void.class;

    /**
     * What happens when the retries give up on a result that the {@link #classifier()} rejected:
     * {@link ExhaustedResult#RETURN RETURN} it (the default), or {@link ExhaustedResult#THROW THROW} a
     * {@code dev.resilience4cata.ResultRejectedException} holding it, which goes to {@code @Fallback} like
     * any other failure. Needs a classifier (setting it without one is a compile error). Decided at compile
     * time: it is not part of the retry's configuration.
     */
    ExhaustedResult onExhaustedResult() default ExhaustedResult.INHERIT;

    /**
     * Retry budget: retries allowed, in percent of the calls this instance received in the last
     * {@link #budgetWindow()}, on top of {@link #budgetMinRetriesPerSecond()}; for example {@code 20} lets
     * one call in five be retried once. Finite, at least 0. The budget is shared by every caller of the
     * instance (see {@link #name()}). Above 0 it turns the budget on; 0, the built-in default, means no
     * budget. {@code -1} means inherit.
     */
    double budgetPercent() default -1;

    /**
     * Retry budget: retries allowed per second of {@link #budgetWindow()} whatever the traffic, so that a
     * method called rarely can still retry. Applies once {@link #budgetPercent()} turns the budget on. At
     * least 0; 0 leaves the percentage alone, which a method called a few times per window can never
     * retry under. {@code -1} means inherit (the built-in default is 10, as in Finagle's {@code RetryBudget}).
     */
    int budgetMinRetriesPerSecond() default -1;

    /**
     * Retry budget: how far back calls and retries are counted, between {@code "1s"} and {@code "1h"}.
     * Empty means inherit (the built-in default is {@code "10s"}).
     */
    String budgetWindow() default "";

    /** Tri-state switch: {@code ""}, {@code "true"} or {@code "false"}. */
    String enabled() default "";
}
