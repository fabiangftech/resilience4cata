package dev.resilience4cata.examples;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.Jitter;
import dev.resilience4cata.annotation.Retry;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/**
 * Retries around a circuit breaker with an explicit {@code abortOn}, which no longer lists
 * {@code CallNotPermittedException}: an open breaker must still stop the retries.
 */
public interface OpenBreakerClient {

    @Retry(maxAttempts = 10, delay = "0", jitter = Jitter.NONE, abortOn = IllegalArgumentException.class)
    @CircuitBreaker(windowSize = 2, minimumCalls = 2, failureRateThreshold = 50, waitInOpen = "1h")
    String sync(String id);

    @Retry(maxAttempts = 10, delay = "0", jitter = Jitter.NONE, abortOn = IllegalArgumentException.class)
    @CircuitBreaker(windowSize = 2, minimumCalls = 2, failureRateThreshold = 50, waitInOpen = "1h")
    CompletableFuture<String> async(String id);

    @Retry(maxAttempts = 10, delay = "0", jitter = Jitter.NONE, abortOn = IllegalArgumentException.class,
            classifier = EveryFailure.class)
    @CircuitBreaker(windowSize = 2, minimumCalls = 2, failureRateThreshold = 50, waitInOpen = "1h")
    String classified(String id);

    /** Calls every exception a failure, {@code CallNotPermittedException} included. */
    final class EveryFailure implements Classifier<String> {
        @Override
        public Outcome classify(@Nullable String result, @Nullable Throwable failure) {
            return failure != null ? Outcome.FAILURE : Outcome.SUCCESS;
        }
    }
}
