package dev.resilience4cata.examples;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.Fallback;
import dev.resilience4cata.annotation.Retry;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jspecify.annotations.Nullable;

/**
 * A classifier that throws inside the breaker layer, under a retry whose classifier would retry that
 * exception: the call must still end at once with the classifier's exception.
 */
public interface ClassifierFailureClient {

    /** An HTTP-like reply. */
    record Reply(int status, String body) {
    }

    @Retry(maxAttempts = 3, delay = "0", classifier = Picky.class)
    @CircuitBreaker(windowSize = 10, minimumCalls = 10, classifier = Picky.class)
    Reply shared(String path) throws IOException;

    @Retry(maxAttempts = 3, delay = "0", classifier = Picky.class)
    @CircuitBreaker(windowSize = 10, minimumCalls = 10, classifier = Picky.class)
    @Fallback(Recover.class)
    Reply sharedWithFallback(String path) throws IOException;

    @Retry(maxAttempts = 3, delay = "0", classifier = RetryAnyFailure.class)
    @CircuitBreaker(windowSize = 10, minimumCalls = 10, classifier = Picky.class)
    Reply different(String path) throws IOException;

    @Retry(maxAttempts = 3, delay = "0")
    @CircuitBreaker(windowSize = 10, minimumCalls = 10, classifier = Picky.class)
    @Fallback(Recover.class)
    Reply unclassifiedRetry(String path) throws IOException;

    @Retry(maxAttempts = 3, delay = "0", classifier = Picky.class)
    @CircuitBreaker(windowSize = 10, minimumCalls = 10, classifier = Picky.class)
    CompletionStage<Reply> sharedAsync(String path);

    @Retry(maxAttempts = 3, delay = "0", classifier = RetryAnyFailure.class)
    @CircuitBreaker(windowSize = 10, minimumCalls = 10, classifier = Picky.class)
    @Fallback(Recover.class)
    CompletionStage<Reply> differentAsync(String path);

    /** Every exception is a failure (so it would be retried); status 999 is a bug in the classifier. */
    final class Picky implements Classifier<Reply> {
        @Override
        public Outcome classify(@Nullable Reply reply, @Nullable Throwable failure) {
            if (failure != null) {
                return Outcome.FAILURE;
            }
            if (reply != null && reply.status() == 999) {
                throw new IllegalStateException("classifier bug");
            }
            return reply == null || reply.status() >= 500 ? Outcome.FAILURE : Outcome.SUCCESS;
        }
    }

    /** Retries every exception and every 5xx reply; never throws. */
    final class RetryAnyFailure implements Classifier<Reply> {
        @Override
        public Outcome classify(@Nullable Reply reply, @Nullable Throwable failure) {
            return failure != null || reply == null || reply.status() >= 500 ? Outcome.FAILURE : Outcome.SUCCESS;
        }
    }

    /** Records the failure it is given and answers with the attempts. */
    final class Recover implements FallbackHandler<Reply> {
        static final List<Throwable> SEEN = new CopyOnWriteArrayList<>();

        @Override
        public Reply apply(FallbackContext context) {
            SEEN.add(context.failure());
            return new Reply(200, "fallback after " + context.attempts() + ": " + context.failure().getMessage());
        }
    }
}
