package dev.resilience4cata.annotation;

/**
 * What a {@link Retry} with a {@code classifier} does when it gives up on a result that the classifier
 * rejected ({@code Outcome.FAILURE}).
 */
public enum ExhaustedResult {
    /** Unset: {@link #RETURN}, unless a bundle or the interface sets it. */
    INHERIT,
    /** Return the last result. {@code @Fallback} does not run, since nothing was thrown. */
    RETURN,
    /**
     * Throw a {@code dev.resilience4cata.ResultRejectedException} holding the last result. It goes to
     * {@code @Fallback} like any other failure.
     */
    THROW
}
