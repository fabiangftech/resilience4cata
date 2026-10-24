package dev.resilience4cata;

import org.jspecify.annotations.Nullable;

/**
 * A call with no result protected by a {@link Pipeline}, for {@link Pipeline#run}: usually a lambda or
 * method reference. It may throw a checked exception, which {@link Pipeline#run} then declares too:
 *
 * <pre>{@code
 * audit.run(() -> client.send(event));   // throws IOException if send does
 * }</pre>
 *
 * <p>It is also a {@link PipelineCall} whose result is always {@code null}, which is how the pipeline
 * runs it through the same layers as {@link Pipeline#execute} without allocating.
 *
 * @param <X> the checked exception the call may throw; {@link RuntimeException} when it throws none
 */
@FunctionalInterface
public interface PipelineRunnable<X extends Throwable> extends PipelineCall<@Nullable Void, X> {

    /**
     * Runs the call once. A pipeline with a retry calls it again after a failure.
     *
     * @throws X if the call fails
     */
    void run() throws X;

    /**
     * Runs the call once through {@link #run()}.
     *
     * @return {@code null}
     * @throws X if the call fails
     */
    @Override
    default @Nullable Void call() throws X {
        run();
        return null;
    }
}
