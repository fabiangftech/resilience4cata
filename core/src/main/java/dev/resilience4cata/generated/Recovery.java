package dev.resilience4cata.generated;

import java.util.concurrent.CompletionStage;

/**
 * The fallback of an asynchronous method, as generated code passes it to {@link FallbackPolicy} or
 * {@link RetryPolicy}. Called only for failures that {@link FallbackPolicy#handles(Throwable)} accepts.
 *
 * @param <T> the result type of the protected method
 */
@FunctionalInterface
public interface Recovery<T> {

    /**
     * Produces the alternative result.
     *
     * @param failure what failed, unwrapped
     * @param attempts number of attempts made, including the first one
     * @return the alternative result
     * @throws Throwable to fail the call instead
     */
    // java:S112: public API used by generated code; fallbacks may rethrow the original failure, whatever it is.
    @SuppressWarnings("java:S112")
    CompletionStage<T> recover(Throwable failure, int attempts) throws Throwable;
}
