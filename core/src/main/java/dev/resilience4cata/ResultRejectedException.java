package dev.resilience4cata;

import org.jspecify.annotations.Nullable;

/**
 * Thrown when a retry gives up on a result that the method's classifier rejected
 * ({@code Outcome.FAILURE}), with {@code @Retry(onExhaustedResult = THROW)} or
 * {@code Pipeline.Builder.onExhaustedResult(THROW)}. Like any other failure, it goes to the
 * {@code @Fallback} (or the pipeline's fallback) if there is one; {@link FallbackContext#failure()} is
 * then this exception, and {@link #result()} the rejected result.
 *
 * <p>Like the library's other exceptions, it has no stack trace: it is created by the retry, not by
 * the code that produced the result.
 */
public final class ResultRejectedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String id;
    private final transient @Nullable Object result;
    private final int attempts;

    /**
     * Creates the exception.
     *
     * @param id id of the retry policy
     * @param result the last result, which the classifier rejected; {@code null} for a {@code void} method
     * @param attempts number of attempts made, including the last one
     */
    public ResultRejectedException(String id, @Nullable Object result, int attempts) {
        super("Retry '" + id + "' gave up after " + attempts + (attempts == 1 ? " attempt" : " attempts")
                + ": the classifier rejected the last result", null, false, false);
        this.id = id;
        this.result = result;
        this.attempts = attempts;
    }

    /** Returns the id of the retry policy. */
    public String id() {
        return id;
    }

    /**
     * Returns the last result, which the classifier rejected: {@code null} for a {@code void} method, or
     * after deserialization (the result is not serialized).
     */
    public @Nullable Object result() {
        return result;
    }

    /**
     * Returns the last result cast to {@code type}.
     *
     * @param type the method's result type
     * @param <R> the method's result type
     * @return the rejected result, possibly {@code null}
     * @throws ClassCastException if the result is not a {@code type}
     */
    public <R> @Nullable R result(Class<R> type) {
        return type.cast(result);
    }

    /** Returns the number of attempts made, including the last one. */
    public int attempts() {
        return attempts;
    }
}
