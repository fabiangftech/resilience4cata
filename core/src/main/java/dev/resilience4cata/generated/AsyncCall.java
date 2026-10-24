package dev.resilience4cata.generated;

import java.util.concurrent.CompletionStage;

/**
 * One asynchronous layer of a generated method: the next policy, or the delegate. It may throw, like
 * the delegate method it wraps; a thrown exception is treated as a failed stage.
 *
 * @param <T> the result type of the protected method
 */
@FunctionalInterface
public interface AsyncCall<T> {

    /**
     * Starts the call.
     *
     * @return the call's stage
     * @throws Throwable if the call fails before returning a stage
     */
    CompletionStage<T> call() throws Throwable;
}
