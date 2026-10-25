package dev.resilience4cata.junit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method that configures the registry {@link ResilienceExtension} builds for each test: named
 * configs, defaults, listeners, {@code ResilienceConfig.applyTo(...)}. The method returns {@code void}
 * and takes one {@link dev.resilience4cata.ResilienceRegistry.Builder}, already set up with the test's
 * clock and seeded jitter; it may be static or not, and is not private.
 *
 * <pre>{@code
 * @ConfigureRegistry
 * void configure(ResilienceRegistry.Builder builder) {
 *     builder.retry("payments", r -> r.maxAttempts(3).delay(Duration.ofSeconds(1)));
 * }
 * }</pre>
 *
 * <p>The methods run once per test, when the test first asks for the registry, on the test instance:
 * an enclosing class's before a {@code @Nested} class's, and a superclass's before a subclass's. The order
 * of several such methods in one class is not specified.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ConfigureRegistry {
}
