package dev.resilience4cata.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Public API that may change in any minor release, until it is settled. The build's API compatibility check
 * (japicmp, which fails on incompatible changes from 1.0) leaves out what carries it.
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.FIELD,
        ElementType.PACKAGE, ElementType.ANNOTATION_TYPE})
public @interface Experimental {

    /** Version in which the element was added. */
    String since() default "";
}
