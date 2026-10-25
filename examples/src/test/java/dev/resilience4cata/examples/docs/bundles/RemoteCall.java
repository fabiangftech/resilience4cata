package dev.resilience4cata.examples.docs.bundles;

// The composed annotation of docs/{en,es}/guides/bundles.md.
// tag::bundles-annotation[]
import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.ResiliencePolicy;
import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.annotation.Timeout;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@ResiliencePolicy
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.TYPE})
@Retry(config = "external")
@CircuitBreaker(config = "external")
@Timeout("2s")
public @interface RemoteCall {
}
// end::bundles-annotation[]
