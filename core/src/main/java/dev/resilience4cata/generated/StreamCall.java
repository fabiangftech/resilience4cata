package dev.resilience4cata.generated;

import java.util.concurrent.CompletionStage;
import org.jspecify.annotations.Nullable;

/**
 * One call of a stream through a {@link dev.resilience4cata.Pipeline} ({@code executeStreamAsync}), as
 * the reactor module's {@code Flux} operator implements it: the boundary of each attempt, and the
 * fallback's value as a stream.
 *
 * @param <T> the element type
 */
@dev.resilience4cata.annotation.Internal
public interface StreamCall<T> {

    /**
     * The boundary of one attempt, inside the retry or the fallback: creates the attempt, runs
     * {@code layers} with it, and returns a stage that fails only if the attempt failed before the stream's
     * first element.
     *
     * @param layers the policies inside the retry or the fallback, ending with {@link StreamAttempt#subscribe()}
     * @return the stage the retry or the fallback waits for
     */
    CompletionStage<Void> attempt(AttemptLayers layers);

    /**
     * The fallback's value, as a stream of one element ({@code null}: an empty stream).
     *
     * @param value what the pipeline's fallback returned
     * @return a stage that ends when that stream does
     */
    CompletionStage<Void> recover(@Nullable T value);
}
