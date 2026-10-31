/**
 * The public side of the Spring Boot integration: {@link dev.resilience4cata.spring.ResilienceRegistryCustomizer}
 * beans adjust the registry before it is built, and {@link dev.resilience4cata.spring.ResilienceHealthIndicator} and
 * {@link dev.resilience4cata.spring.ResilienceEndpoint} report the registry to Spring Boot Actuator. The
 * {@code resilience4cata.spring.*} switches are documented by their property names, not by a Java type.
 */
@NullMarked
package dev.resilience4cata.spring;

import org.jspecify.annotations.NullMarked;
