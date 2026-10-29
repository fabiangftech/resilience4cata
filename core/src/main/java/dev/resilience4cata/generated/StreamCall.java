package dev.resilience4cata.generated;

import java.util.concurrent.CompletionStage;
import org.jspecify.annotations.Nullable;

/**
 * One call of a stream through a {@link dev.resilience4cata.Pipeline} ({@code executeStreamAsync}), as
 * the reactor module's {@code Flux} operator implements it: the elements the policies listen to, the
 * boundary of each attempt, the source of each attempt, and the fallback's value as a stream.
 *
 * @param <T> the element type
 */
public interface StreamCall<T> extends Elements {

    /**
     * The boundary of one attempt, inside the retry or the fallback: runs {@code next} and returns a
     * stage that fails only if the attempt failed before the stream's first element.
     *
     * @param next the policies inside the retry or the fallback, ending with {@link #subscribe()}
     * @return the stage the retry or the fallback waits for
     */
    CompletionStage<Void> attempt(AsyncCall<Void> next);

    /**
     * Starts one attempt: subscribes to the source stream.
     *
     * @return a stage that ends when the attempt's stream does
     */
    CompletionStage<Void> subscribe();

    /**
     * The fallback's value, as a stream of one element ({@code null}: an empty stream).
     *
     * @param value what the pipeline's fallback returned
     * @return a stage that ends when that stream does
     */
    CompletionStage<Void> recover(@Nullable T value);
}
