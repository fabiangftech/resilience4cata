package dev.resilience4cata.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a user-defined annotation that bundles several policies, for example:
 *
 * <pre>{@code
 * @ResiliencePolicy
 * @Retention(RetentionPolicy.CLASS)
 * @Target({ElementType.METHOD, ElementType.TYPE})
 * @Retry(config = "external")
 * @CircuitBreaker(config = "external")
 * @Timeout("2s")
 * public @interface ExternalCall {}
 * }</pre>
 *
 * <p>Annotations written directly on an element win over the bundle. Two bundles that set the
 * same attribute to different values on the same element are a compile error.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.ANNOTATION_TYPE)
public @interface ResiliencePolicy {
}
