package dev.resilience4cata.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Starts the same call again, alongside the first one, when it is slow or fails, and keeps the first
 * success: hedged requests, against tail latency.
 *
 * <p><b>Only for idempotent calls.</b> A hedged method is called several times on purpose, even when
 * nothing fails, with the same arguments, and the calls that lose keep running until they notice the
 * cancel. Use it on reads and other operations that are safe to repeat; never on payments, inserts or
 * anything that must happen once. The annotation is the declaration that the method is idempotent.
 *
 * <p>Asynchronous methods only (returning {@code CompletionStage<T>} or {@code CompletableFuture<T>});
 * on a synchronous method it is a compile error. It takes the place of {@code @Retry} in the order of
 * policies, and cannot be combined with it on one method: a hedge already starts a new attempt when one
 * fails. Each attempt goes through the inner policies (circuit breaker, rate limiter, timeout, bulkhead)
 * on its own.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface Hedge {

    /** Stateful instance id: methods with the same name share the hedge budget. Empty means one instance per method. */
    String name() default "";

    /** Named configuration to start from. Empty means {@code default}. */
    String config() default "";

    /**
     * Attempts in flight at most, including the first one, between 2 and 10. {@code -1} means inherit (the
     * built-in default is 2: one hedge).
     */
    int maxAttempts() default -1;

    /**
     * How long an attempt runs without a result before the next one starts, for example {@code "50ms"};
     * set it near the dependency's 95th percentile latency. {@code "0"} starts every attempt at once.
     * Empty means inherit (the built-in default is {@code "1s"}).
     */
    String delay() default "";

    /**
     * Failures after which the next attempt starts at once instead of after {@link #delay()}. A failure not
     * listed here ends the call, as one listed in {@link #abortOn()} does. Empty means inherit (the built-in
     * default is {@code Exception}).
     */
    Class<? extends Throwable>[] hedgeOn() default {};

    /**
     * Failures that end the call at once, cancelling the other attempts; they win over {@link #hedgeOn()}.
     * Empty means inherit (the built-in default is empty).
     */
    Class<? extends Throwable>[] abortOn() default {};

    /**
     * Hedge budget: hedges allowed, in percent of the calls this instance received in the last
     * {@link #budgetWindow()}, on top of {@link #budgetMinHedgesPerSecond()}. Finite, at least 0; 0 turns
     * the budget off. {@code -1} means inherit (the built-in default is 10: the budget is on).
     */
    double budgetPercent() default -1;

    /**
     * Hedge budget: hedges allowed per second of {@link #budgetWindow()} whatever the traffic. At least 0.
     * {@code -1} means inherit (the built-in default is 1).
     */
    int budgetMinHedgesPerSecond() default -1;

    /**
     * Hedge budget: how far back calls and hedges are counted, between {@code "1s"} and {@code "1h"}.
     * Empty means inherit (the built-in default is {@code "10s"}).
     */
    String budgetWindow() default "";

    /** Tri-state switch: {@code ""}, {@code "true"} or {@code "false"}. */
    String enabled() default "";
}
