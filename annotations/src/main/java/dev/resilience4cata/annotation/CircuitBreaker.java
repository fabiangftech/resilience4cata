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

    /**
     * Locks the values set in this annotation (directly, at type level or through a bundle): per-instance
     * overrides ({@code instances.<id>.*} in external configuration, or {@code *Instance(...)} on the registry
     * builder) cannot change them, and an override that tries to is ignored and logged. Attributes the
     * annotation leaves unset can still be overridden. Named configurations never override an annotation.
     */
    boolean locked() default false;

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

    /** How long the breaker may stay half-open before opening again, for example {@code "10s"}. {@code "0"} means no limit. */
    String maxWaitInHalfOpen() default "";

    /** Exceptions recorded as failures. Empty means inherit. */
    Class<? extends Throwable>[] recordOn() default {};

    /** Exceptions that count neither as success nor as failure. */
    Class<? extends Throwable>[] ignoreOn() default {};

    /**
     * Classifier for results and exceptions: a class implementing {@code dev.resilience4cata.Classifier<T>},
     * where the method's return type (or {@code T} of its {@code CompletionStage<T>}) is assignable to
     * {@code T}, with a public no-argument constructor. One instance is created per method (shared with
     * {@code @Retry} if it uses the same class). The annotation processor checks it. {@code void.class}
     * means none.
     *
     * <p>With a classifier, the breaker records each call as the classifier says: {@code FAILURE} counts
     * as a failure, {@code SUCCESS} as a success, and {@code IGNORE} is not recorded (a half-open probe
     * gives its permit back). It replaces {@code recordOn} (setting both is a compile error);
     * {@code ignoreOn} still wins over it. An exception thrown by the classifier is recorded as a
     * failure and propagates as the call's failure.
     */
    Class<?> classifier() default void.class;

    /**
     * Whether the policy acts at all; {@code false} lets every call through. Not set means inherit (the built-in
     * default is {@code true}).
     */
    boolean enabled() default true;
}
