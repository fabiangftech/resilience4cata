/**
 * Project Reactor support. Annotated methods that return a {@code Mono} or a {@code Flux} need only this module on
 * the class path (the processor generates the rest); {@link dev.resilience4cata.reactor.MonoPipelineOperator} and
 * {@link dev.resilience4cata.reactor.FluxPipelineOperator} apply a {@link dev.resilience4cata.Pipeline} to a
 * {@code Mono} or a {@code Flux} that cannot be annotated.
 */
@NullMarked
package dev.resilience4cata.reactor;

import org.jspecify.annotations.NullMarked;
