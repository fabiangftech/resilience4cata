package dev.resilience4cata.examples;

import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.examples.HalfOpenProbeClient.Buggy;
import java.io.IOException;

/** {@link HalfOpenProbeClient}'s sync shapes in the generated-subclass style. */
public class HalfOpenProbeService {

    @CircuitBreaker(windowSize = 2, minimumCalls = 2, failureRateThreshold = 50, waitInOpen = "10s", halfOpenCalls = 1,
            classifier = Buggy.class)
    public String breaker(boolean fail) throws IOException {
        return answer(fail);
    }

    @Retry(maxAttempts = 3, delay = "0", classifier = Buggy.class)
    @CircuitBreaker(windowSize = 2, minimumCalls = 2, failureRateThreshold = 50, waitInOpen = "10s", halfOpenCalls = 1,
            classifier = Buggy.class)
    public String sharedClassifier(boolean fail) throws IOException {
        return answer(fail);
    }

    static String answer(boolean fail) throws IOException {
        if (fail) {
            throw new IOException("down");
        }
        return "ok";
    }
}
