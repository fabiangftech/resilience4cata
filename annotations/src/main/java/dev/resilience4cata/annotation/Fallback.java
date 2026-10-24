package dev.resilience4cata.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Provides an alternative result when the call fails or is rejected. Set exactly one of
 * {@link #value()} or {@link #method()}.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface Fallback {

    /**
     * Handler class. Must implement {@code dev.resilience4cata.FallbackHandler<T>}, where {@code T} matches
     * the method's return type; the annotation processor checks it. {@code void.class} means unset.
     */
    Class<?> value() default void.class;

    /** Name of a sibling method with a compatible signature, checked at compile time. */
    String method() default "";

    /** Exceptions that trigger the fallback. */
    Class<? extends Throwable>[] applyOn() default {Throwable.class};

    /** Exceptions that never trigger the fallback. */
    Class<? extends Throwable>[] skipOn() default {};
}
