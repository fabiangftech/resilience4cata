package dev.resilience4cata.micronaut.fixtures;

import io.micronaut.aop.Around;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** A Micronaut around advice, like {@code @Transactional}: {@link AuditInterceptor} counts the calls it sees. */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
@Around
public @interface Audited {
}
