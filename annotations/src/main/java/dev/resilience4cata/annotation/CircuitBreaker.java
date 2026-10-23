package dev.resilience4cata.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Stops calling a failing dependency until it recovers. */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface CircuitBreaker {

    /** Stateful instance id. Empty means one instance per method. Methods sharing a name share the breaker. */
    String name() default "";

    /** Named configuration to start from. Empty means {@code default}. */
    String config() default "";

    /** How the sliding window is measured. */
    WindowType windowType() default WindowType.INHERIT;

    /** Window size: number of calls for {@link WindowType#COUNT}, seconds for {@link WindowType#TIME}. */
    int windowSize() default -1;

    /** Minimum number of calls before the failure rate is evaluated. */
    int minimumCalls() default -1;

    /** Failure rate, in percent ({@code 0 < x <= 100}), that opens the breaker. */
    float failureRateThreshold() default -1f;

    /** Slow-call rate, in percent ({@code 0 < x <= 100}), that opens the breaker. */
    float slowCallRateThreshold() default -1f;

    /** Duration above which a call counts as slow, for example {@code "2s"}. */
    String slowCallDuration() default "";

    /** How long the breaker stays open before allowing probe calls, for example {@code "30s"}. */
    String waitInOpen() default "";

    /** Number of probe calls allowed in the half-open state. */
    int halfOpenCalls() default -1;

    /** Exceptions recorded as failures. Empty means inherit. */
    Class<? extends Throwable>[] recordOn() default {};

    /** Exceptions that count neither as success nor as failure. */
    Class<? extends Throwable>[] ignoreOn() default {};

    /**
     * Classifier for results and exceptions. Must implement {@code dev.resilience4cata.Classifier};
     * the annotation processor checks it. {@code void.class} means inherit.
     */
    Class<?> classifier() default void.class;

    /** Tri-state switch: {@code ""}, {@code "true"} or {@code "false"}. */
    String enabled() default "";
}
