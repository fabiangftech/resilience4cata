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
    // java:S112: public API used by generated code; it wraps a delegate method that may throw anything.
    @SuppressWarnings("java:S112")
    CompletionStage<T> call() throws Throwable;

    /**
     * Views a stage of a subtype of {@code T} as a stage of {@code T}, without allocating. Generated code
     * uses it for methods that return {@code CompletionStage<? extends T>} (or {@code ?}): the policies
     * work with {@code T}, the wildcard's upper bound.
     *
     * <p>Safe because the policies only read the stages they are given (they observe completion and may
     * cancel them; they never complete them with a value), and the generated method returns the result
     * again as a stage of {@code ? extends T}, so no caller can put a {@code T} into it either.
     *
     * @param stage a stage of some subtype of {@code T}, as the delegate returned it
     * @param <T> the upper bound of the method's wildcard
     * @return the same stage
     */
    @SuppressWarnings("unchecked")
    static <T> CompletionStage<T> widen(CompletionStage<? extends T> stage) {
        return (CompletionStage<T>) stage;
    }
}
