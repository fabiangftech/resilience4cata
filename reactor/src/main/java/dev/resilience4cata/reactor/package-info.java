/**
 * Project Reactor support. Annotated methods that return a {@code Mono} need only this module on the
 * class path (the processor generates the rest); {@link dev.resilience4cata.reactor.PipelineOperator}
 * applies a {@link dev.resilience4cata.Pipeline} to a {@code Mono} that cannot be annotated.
 */
@NullMarked
package dev.resilience4cata.reactor;

import org.jspecify.annotations.NullMarked;
