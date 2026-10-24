package dev.resilience4cata.examples;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.ResultRejectedException;
import dev.resilience4cata.annotation.ExhaustedResult;
import dev.resilience4cata.annotation.Fallback;
import dev.resilience4cata.annotation.Retry;
import java.io.IOException;
import java.util.concurrent.CompletionStage;
import org.jspecify.annotations.Nullable;

/** Retries that throw, instead of returning, the last result their classifier rejected. */
public interface ExhaustedResultClient {

    /** An HTTP-like reply. */
    record Reply(int status, String body) {
    }

    @Retry(maxAttempts = 3, delay = "0", classifier = Status.class, onExhaustedResult = ExhaustedResult.THROW)
    Reply get(String path) throws IOException;

    @Retry(maxAttempts = 3, delay = "0", classifier = Status.class)
    Reply returning(String path) throws IOException;

    @Retry(maxAttempts = 2, delay = "0", classifier = Status.class, onExhaustedResult = ExhaustedResult.THROW)
    @Fallback(method = "rejected")
    Reply withSiblings(String path) throws IOException;

    @Retry(maxAttempts = 2, delay = "0", classifier = Status.class, onExhaustedResult = ExhaustedResult.THROW)
    @Fallback(Stale.class)
    Reply withHandler(String path) throws IOException;

    @Retry(maxAttempts = 2, delay = "0", classifier = Status.class, onExhaustedResult = ExhaustedResult.THROW)
    @Fallback(method = "rejected")
    CompletionStage<Reply> getAsync(String path);

    @Retry(maxAttempts = 3, delay = "0", classifier = Status.class)
    @Fallback(method = "rejected")
    Reply brokenClassifier(String path) throws IOException;

    @Retry(maxAttempts = 3, delay = "0", classifier = Status.class)
    CompletionStage<Reply> brokenClassifierAsync(String path);

    default Reply rejected(String path, ResultRejectedException e) {
        Reply last = e.result(Reply.class);
        return new Reply(200, "rejected " + (last == null ? "-" : last.status()) + " after " + e.attempts());
    }

    default Reply rejected(String path, Throwable e) {
        return new Reply(200, "fallback: " + e.getMessage());
    }

    /** 5xx is a failure, I/O errors are failures, status 999 is a bug in the classifier. */
    final class Status implements Classifier<Reply> {
        @Override
        public Outcome classify(@Nullable Reply reply, @Nullable Throwable failure) {
            if (failure != null) {
                return failure instanceof IOException ? Outcome.FAILURE : Outcome.IGNORE;
            }
            int status = reply == null ? 500 : reply.status();
            if (status == 999) {
                throw new IllegalStateException("classifier bug");
            }
            return status >= 500 ? Outcome.FAILURE : Outcome.SUCCESS;
        }
    }

    /** Returns the rejected reply, marked as stale. */
    final class Stale implements FallbackHandler<Reply> {
        @Override
        public Reply apply(FallbackContext context) throws Throwable {
            if (context.failure() instanceof ResultRejectedException rejected && rejected.result() instanceof Reply last) {
                return new Reply(last.status(), "stale after " + context.attempts());
            }
            throw context.failure();
        }
    }
}
