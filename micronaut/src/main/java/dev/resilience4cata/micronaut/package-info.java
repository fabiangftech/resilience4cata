/**
 * Micronaut 5 integration for resilience4cata: a {@link dev.resilience4cata.ResilienceRegistry} bean configured
 * from the application's {@code resilience4cata.*} configuration, beans of annotated interfaces wrapped with their
 * generated class ({@link dev.resilience4cata.micronaut.ResilientBean}), and optional Micrometer metrics, a health
 * indicator and a read-only management endpoint. Nothing runs on the call path: the protection is the generated
 * code's.
 */
@NullMarked
package dev.resilience4cata.micronaut;

import org.jspecify.annotations.NullMarked;
