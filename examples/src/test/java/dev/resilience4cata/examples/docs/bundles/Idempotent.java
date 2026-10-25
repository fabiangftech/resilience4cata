package dev.resilience4cata.examples.docs.bundles;

import dev.resilience4cata.annotation.ResiliencePolicy;
import dev.resilience4cata.annotation.Retry;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** A bundle that builds on another one. */
// tag::bundles-nested[]
@ResiliencePolicy
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.TYPE})
@RemoteCall
@Retry(maxAttempts = 5)   // beats RemoteCall's retry attributes, keeps its config = "external"
public @interface Idempotent {
}
// end::bundles-nested[]
