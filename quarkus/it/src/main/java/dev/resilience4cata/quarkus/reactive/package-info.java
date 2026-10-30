/**
 * {@code Mono} and {@code Flux} beans behind HTTP endpoints: an annotated class and a wrapped annotated interface,
 * with the policies that have a reactive form. {@code ReactiveSmokeTest} calls them on the JVM and
 * {@code ReactiveSmokeIT} on the packaged application, the native image included ({@code testNative}).
 */
package dev.resilience4cata.quarkus.reactive;
