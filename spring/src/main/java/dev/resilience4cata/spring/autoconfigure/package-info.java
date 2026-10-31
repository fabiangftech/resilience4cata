/**
 * The auto-configuration classes, listed in
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}, and the binding of the
 * {@code resilience4cata.spring.*} switches. Never component-scanned and not API: exported only so that
 * {@code @SpringBootApplication(exclude = ResilienceAutoConfiguration.class)} works on the module path. What is a
 * contract is the auto-configuration class names, the bean names and the property names; method signatures are
 * not, and are excluded from the compatibility check.
 */
@NullMarked
package dev.resilience4cata.spring.autoconfigure;

import org.jspecify.annotations.NullMarked;
