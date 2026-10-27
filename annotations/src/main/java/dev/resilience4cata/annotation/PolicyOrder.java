package dev.resilience4cata.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Changes the order in which the policies of a method are nested. By default it is Fallback → Retry | Hedge →
 * CircuitBreaker → RateLimiter → AdaptiveLimit → Timeout → Bulkhead → method, outermost first.
 *
 * <p>The listed layers move together, in the listed order, to the place of the outermost of them; the layers not
 * listed keep their default order around them. So {@code @PolicyOrder({TIMEOUT, RETRY})} gives Fallback → Timeout →
 * Retry → CircuitBreaker → …: one timeout for the whole retry loop instead of one per attempt. Layers the method does
 * not have are ignored. The fallback is always outermost.
 *
 * <p>The order is decided at compile time and changes the generated code; it cannot come from configuration. On a
 * method, on a type (for every method) or in a {@link ResiliencePolicy} bundle; the method's wins over the type's,
 * and a direct annotation over a bundle's. Listing a layer twice is a compile error.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface PolicyOrder {

    /** The layers to move, outermost first. */
    Layer[] value();
}
