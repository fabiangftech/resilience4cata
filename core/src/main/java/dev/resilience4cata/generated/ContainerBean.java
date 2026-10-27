package dev.resilience4cata.generated;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a subclass the annotation processor generated for a dependency injection container
 * ({@code -Ar4c.inject=micronaut}): it repeats the annotated class's own type annotations (scope, qualifiers), so the
 * container can create it in place of the class. The Micronaut integration reads it to tell such a subclass from one
 * compiled without the option, which a mirrored {@code @Inject} constructor makes a bean without the class's scope.
 * For generated code only.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ContainerBean {
}
