package dev.resilience4cata.examples;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.ResultRejectedException;
import dev.resilience4cata.annotation.ExhaustedResult;
import dev.resilience4cata.annotation.Fallback;
import dev.resilience4cata.annotation.Retry;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.jspecify.annotations.Nullable;

/**
 * An annotated class that combines features built separately: a generic method with a sibling fallback, a
 * wildcard async return type, and a classifier that throws on an exhausted result. Each call to itself goes
 * through the generated subclass.
 */
public class ClassCombinedService {

    /** Results to return, in order; an {@link IOException} entry is thrown instead. */
    final Deque<Object> script = new ArrayDeque<>();
    int calls;

    public ClassCombinedService() {
    }

    private Object next() throws IOException {
        calls++;
        Object value = script.isEmpty() ? "ok" : script.pop();
        if (value instanceof IOException failure) {
            throw failure;
        }
        return value;
    }

    @Retry(maxAttempts = 2, delay = "0")
    @Fallback(method = "load")
    public <T> T load(Class<T> type, String id) throws IOException {
        return type.cast(next());
    }

    protected <U> U load(Class<U> type, String id, IOException e) {
        return type.cast("fallback " + id);
    }

    @Retry(maxAttempts = 3, delay = "0")
    public CompletionStage<? extends CharSequence> find(String id) {
        try {
            return CompletableFuture.completedFuture((String) next());
        } catch (IOException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    @Retry(maxAttempts = 2, delay = "0", classifier = NotBusy.class, onExhaustedResult = ExhaustedResult.THROW)
    @Fallback(method = "status")
    public String status(String id) throws IOException {
        return (String) next();
    }

    protected String status(String id, ResultRejectedException e) {
        return "rejected after " + e.attempts();
    }

    /** Calls itself: the generated subclass intercepts {@code this.load(...)}. */
    public String describe(String id) throws IOException {
        return "described " + this.load(String.class, id);
    }

    /** "busy" is a failure result. */
    public static final class NotBusy implements Classifier<String> {
        @Override
        public Outcome classify(@Nullable String result, @Nullable Throwable failure) {
            if (failure != null) {
                return Outcome.FAILURE;
            }
            return "busy".equals(result) ? Outcome.FAILURE : Outcome.SUCCESS;
        }
    }
}
