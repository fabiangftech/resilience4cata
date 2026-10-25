package dev.resilience4cata.generated;

import java.util.Objects;

/**
 * Carries an exception thrown by a breaker's classifier out to the retry around the breaker, so that the
 * retry ends the call with it at once instead of classifying it again as the method's own failure.
 *
 * <p>Only a breaker layer inside a retry throws it (in generated code, in {@code Pipeline}, or as the
 * failure of the stage returned by {@link CircuitBreakerPolicy#executeAsync(AsyncCall,
 * dev.resilience4cata.Classifier, boolean)}), and the retry always unwraps it: callers never see it. It
 * is created only when a classifier throws, which is a bug in the classifier, and has no stack trace.
 */
public final class ClassifierFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * Wraps a classifier's exception.
     *
     * @param failure what the classifier threw
     */
    public ClassifierFailure(Throwable failure) {
        super(null, Objects.requireNonNull(failure, "failure"), false, false);
    }

    /**
     * The classifier's exception, for a generated {@code throw}: returned if it is a
     * {@link RuntimeException}, thrown otherwise. Generated code writes
     * {@code try { throw w.failure(); } catch (Throwable c) { ... }}, so that {@code c} is the classifier's
     * exception itself and {@code throw c} is a precise rethrow that declares nothing.
     *
     * @return the classifier's exception, if it is a {@code RuntimeException}
     */
    public RuntimeException failure() {
        Throwable failure = getCause();
        if (failure instanceof RuntimeException e) {
            return e;
        }
        throw ClassifierFailure.<RuntimeException>sneaky(Objects.requireNonNull(failure));
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> E sneaky(Throwable failure) throws E {
        throw (E) failure;
    }
}
