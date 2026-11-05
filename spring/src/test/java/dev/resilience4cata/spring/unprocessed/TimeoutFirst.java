package dev.resilience4cata.spring.unprocessed;

import static dev.resilience4cata.annotation.Layer.RETRY;
import static dev.resilience4cata.annotation.Layer.TIMEOUT;

import dev.resilience4cata.annotation.PolicyOrder;
import dev.resilience4cata.annotation.ResiliencePolicy;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** A bundle with only an order and no policy: it protects nothing, so the processor generates nothing for it. */
@ResiliencePolicy
@PolicyOrder({TIMEOUT, RETRY})
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface TimeoutFirst {
}
