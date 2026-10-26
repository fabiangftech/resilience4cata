/**
 * The public side of the Spring Boot integration: {@link dev.resilience4cata.spring.ResilienceRegistryCustomizer}
 * beans adjust the registry before it is built, {@link dev.resilience4cata.spring.ResilienceSpringProperties}
 * holds the {@code resilience4cata.spring.*} switches, and
 * {@link dev.resilience4cata.spring.ResilienceHealthIndicator} and
 * {@link dev.resilience4cata.spring.ResilienceEndpoint} report the registry to Spring Boot Actuator.
 */
@NullMarked
package dev.resilience4cata.spring;

import org.jspecify.annotations.NullMarked;
