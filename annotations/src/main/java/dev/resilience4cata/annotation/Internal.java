package dev.resilience4cata.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Not part of the public API, even if visible: public only so that another resilience4cata module (or the
 * {@code ServiceLoader}) can reach it. Can change or disappear in any release; the build's API compatibility check
 * (japicmp) leaves out what carries it. Members of {@code dev.resilience4cata.generated} that generated code calls
 * never carry it: they are binary API for the whole major version.
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.FIELD,
        ElementType.PACKAGE})
public @interface Internal {
}
