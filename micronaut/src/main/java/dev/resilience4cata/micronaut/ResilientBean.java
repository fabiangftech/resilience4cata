package dev.resilience4cata.micronaut;

import io.micronaut.context.annotation.AliasFor;
import io.micronaut.context.annotation.Bean;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Protects a bean with the policies of the annotated interface it implements: when Micronaut creates the bean,
 * it is replaced by the generated wrapper, {@code Resilient.of(value, bean, registry)}.
 *
 * <pre>{@code
 * @Singleton
 * @ResilientBean(Payments.class)
 * final class HttpPayments implements Payments { ... }
 * }</pre>
 *
 * <p>The wrapper implements {@code value} only, so the bean is exposed only as {@code value} (this annotation
 * sets {@code @Bean(typed = value)}): inject {@code Payments}, not {@code HttpPayments}. Exposing it as another
 * type is an error ({@code R4C-MN-001}), and so is a {@code value} that the bean does not implement
 * ({@code R4C-MN-002}). Use it on {@code @Singleton}, {@code @Prototype} or {@code @Context} beans; scopes that
 * Micronaut proxies ({@code @Refreshable}, {@code @RequestScope}, {@code @ThreadLocal}) are rejected
 * ({@code R4C-MN-003}). Micronaut AOP on the bean ({@code @Transactional}, {@code @Cacheable}) runs inside the
 * policies: each retry goes through the bean's interceptors again.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Bean
public @interface ResilientBean {

    /**
     * The annotated interface whose generated wrapper protects the bean.
     *
     * @return the interface
     */
    @AliasFor(annotation = Bean.class, member = "typed")
    Class<?> value();
}
