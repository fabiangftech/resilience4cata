package dev.resilience4cata.examples;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.Fallback;
import dev.resilience4cata.annotation.Retry;
import java.io.IOException;
import java.util.concurrent.CompletionStage;
import org.jspecify.annotations.Nullable;

/** Methods whose retries and breakers decide on results as well as exceptions, through a classifier. */
public interface ClassifiedClient {

    /** An HTTP-like reply. */
    record Reply(int status, String body) {
    }

    @Retry(maxAttempts = 3, delay = "0", classifier = HttpStatusClassifier.class)
    @CircuitBreaker(windowSize = 10, minimumCalls = 10, classifier = HttpStatusClassifier.class)
    Reply get(String path) throws IOException;

    @CircuitBreaker(name = "classified-strict", windowSize = 2, minimumCalls = 2, waitInOpen = "1h",
            classifier = HttpStatusClassifier.class)
    Reply probe(String path) throws IOException;

    @Retry(maxAttempts = 2, delay = "0", classifier = HttpStatusClassifier.class)
    @Fallback(method = "cached")
    Reply withFallback(String path) throws IOException;

    @Retry(maxAttempts = 3, delay = "0", classifier = Broken.class)
    @CircuitBreaker(name = "classified-broken", windowSize = 2, minimumCalls = 2, waitInOpen = "1h",
            classifier = Broken.class)
    @Fallback(method = "cached")
    Reply broken(String path) throws IOException;

    @Retry(maxAttempts = 3, delay = "0", classifier = HttpStatusClassifier.class)
    @CircuitBreaker(name = "classified-async", windowSize = 6, minimumCalls = 6, waitInOpen = "1h",
            classifier = HttpStatusClassifier.class)
    @Fallback(method = "cached")
    CompletionStage<Reply> getAsync(String path);

    @Retry(maxAttempts = 3, delay = "0", classifier = FailOnIo.class)
    void send(String path) throws IOException;

    default Reply cached(String path, Throwable e) {
        return new Reply(200, "cached " + path + ": " + e.getMessage());
    }

    /** 5xx and 429 replies and I/O errors are failures; other exceptions are not counted and not retried. */
    final class HttpStatusClassifier implements Classifier<Reply> {
        @Override
        public Outcome classify(@Nullable Reply reply, @Nullable Throwable failure) {
            if (failure != null) {
                return failure instanceof IOException ? Outcome.FAILURE : Outcome.IGNORE;
            }
            int status = reply == null ? 500 : reply.status();
            return status >= 500 || status == 429 ? Outcome.FAILURE : Outcome.SUCCESS;
        }
    }

    /** For void methods, where the result is always {@code null}. */
    final class FailOnIo implements Classifier<Object> {
        @Override
        public Outcome classify(@Nullable Object result, @Nullable Throwable failure) {
            return failure instanceof IOException ? Outcome.FAILURE : Outcome.SUCCESS;
        }
    }

    /** A classifier with a bug. */
    final class Broken implements Classifier<Reply> {
        @Override
        public Outcome classify(@Nullable Reply reply, @Nullable Throwable failure) {
            throw new IllegalStateException("classifier bug");
        }
    }
}
