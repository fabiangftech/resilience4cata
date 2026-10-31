package dev.resilience4cata;

import org.jspecify.annotations.Nullable;

/**
 * Decides whether a call counts as a success, a failure, or neither. Covers both results and
 * exceptions, so one class can express rules such as "HTTP 5xx and 429 are failures".
 *
 * @param <T> the result type of the protected method
 */
@FunctionalInterface
public interface Classifier<T> {

    /**
     * Classifies one call. Exactly one of {@code result} or {@code failure} is meaningful:
     * {@code failure} is {@code null} when the call returned normally.
     *
     * @param result the value returned, when the call did not throw
     * @param failure the exception thrown, or {@code null}
     * @return how the policy should count the call
     */
    Outcome classify(@Nullable T result, @Nullable Throwable failure);

    /**
     * How a policy counts a call. Constants may be added in minor versions: keep a {@code default} branch in a
     * {@code switch} over it.
     */
    enum Outcome {
        /** Counts as a success. */
        SUCCESS,
        /** Counts as a failure: recorded by circuit breakers, retried by retries. */
        FAILURE,
        /** Not counted and not retried. */
        IGNORE
    }
}
