package dev.resilience4cata.micronaut.proxies;

import io.micronaut.aop.Around;
import io.micronaut.context.annotation.Type;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** An application's own around advice, counted by {@link Auditor}. */
@Around
@Type(Auditor.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface Audit {
}
