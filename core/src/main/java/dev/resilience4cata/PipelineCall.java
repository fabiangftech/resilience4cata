package dev.resilience4cata;

/**
 * The call protected by a {@link Pipeline}: usually a lambda or method reference. It may throw a
 * checked exception, which {@link Pipeline#execute} then declares too:
 *
 * <pre>{@code
 * Receipt receipt = pipeline.execute(() -> client.charge(order));   // throws IOException if charge does
 * }</pre>
 *
 * @param <T> the result type
 * @param <X> the checked exception the call may throw; {@link RuntimeException} when it throws none
 */
@FunctionalInterface
public interface PipelineCall<T, X extends Throwable> {

    /**
     * Runs the call once. A pipeline with a retry calls it again after a failure.
     *
     * @return the result
     * @throws X if the call fails
     */
    T call() throws X;
}
