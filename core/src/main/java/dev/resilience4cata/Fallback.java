package dev.resilience4cata;

/**
 * Produces an alternative result when a call fails or is rejected. Referenced from
 * {@code @Fallback(MyFallback.class)}; the annotation processor checks that {@code T} matches the
 * method's return type (or the {@code T} of a {@code CompletionStage<T>}).
 *
 * <p>Implementations need a public no-argument constructor. One handler is created per instance,
 * not per call.
 *
 * @param <T> the result type of the protected method
 */
@FunctionalInterface
public interface Fallback<T> {

    /**
     * Returns the alternative result, or throws to propagate a failure.
     *
     * @param context what failed, and the arguments of the original call
     * @return the alternative result
     * @throws Throwable to fail the call instead
     */
    T apply(FallbackContext context) throws Throwable;
}
