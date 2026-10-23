package dev.resilience4cata.examples;

import dev.resilience4cata.annotation.Jitter;
import dev.resilience4cata.annotation.ResiliencePolicy;
import dev.resilience4cata.annotation.Retry;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** A composed annotation: one name for a reusable policy bundle. */
@ResiliencePolicy
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.TYPE})
@Retry(config = "external", delay = "1ms", jitter = Jitter.NONE)
public @interface ExternalCall {
}
