package dev.resilience4cata.examples;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.Retry;
import java.io.IOException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Mono;

/**
 * Breakers whose classifier can throw while they are half-open, with one probe: the probe must be recorded (as a
 * failure), never lost, or the breaker would stay half-open and reject every call for good (Resilience4j issue #2324).
 * Each method has its own breaker; every shape of generated layer that classifies is here.
 */
public interface HalfOpenProbeClient {

    @CircuitBreaker(windowSize = 2, minimumCalls = 2, failureRateThreshold = 50, waitInOpen = "10s", halfOpenCalls = 1,
            classifier = Buggy.class)
    String breaker(boolean fail) throws IOException;

    @Retry(maxAttempts = 3, delay = "0")
    @CircuitBreaker(windowSize = 2, minimumCalls = 2, failureRateThreshold = 50, waitInOpen = "10s", halfOpenCalls = 1,
            classifier = Buggy.class)
    String insideRetry(boolean fail) throws IOException;

    @Retry(maxAttempts = 3, delay = "0", classifier = Buggy.class)
    @CircuitBreaker(windowSize = 2, minimumCalls = 2, failureRateThreshold = 50, waitInOpen = "10s", halfOpenCalls = 1,
            classifier = Buggy.class)
    String sharedClassifier(boolean fail) throws IOException;

    @CircuitBreaker(windowSize = 2, minimumCalls = 2, failureRateThreshold = 50, waitInOpen = "10s", halfOpenCalls = 1,
            classifier = Buggy.class)
    CompletionStage<String> async(boolean fail);

    @CircuitBreaker(windowSize = 2, minimumCalls = 2, failureRateThreshold = 50, waitInOpen = "10s", halfOpenCalls = 1,
            classifier = Buggy.class)
    Mono<String> mono(boolean fail);

    /**
     * Results {@code "bad"} and every exception are failures, other results successes; while {@link #BROKEN} is set it
     * throws instead of answering.
     */
    final class Buggy implements Classifier<String> {
        static final AtomicBoolean BROKEN = new AtomicBoolean();

        @Override
        public Outcome classify(@Nullable String result, @Nullable Throwable failure) {
            if (BROKEN.get()) {
                throw new IllegalStateException("classifier bug");
            }
            return failure != null || "bad".equals(result) ? Outcome.FAILURE : Outcome.SUCCESS;
        }
    }
}
