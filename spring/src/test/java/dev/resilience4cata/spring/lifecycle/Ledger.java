package dev.resilience4cata.spring.lifecycle;

import dev.resilience4cata.annotation.Backoff;
import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.Jitter;
import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.annotation.Timeout;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

/** An annotated class whose instance names are the same in every context. */
public class Ledger {

    public final AtomicInteger asyncAttempts = new AtomicInteger();
    public volatile boolean failing;

    @CircuitBreaker(name = "ledger", windowSize = 2, minimumCalls = 2, failureRateThreshold = 50, waitInOpen = "1h")
    public String post(String entry) {
        if (failing) {
            throw new IllegalStateException("down");
        }
        return "posted " + entry;
    }

    /** Fails its first attempt; the retry's second one comes after the delay. */
    @Retry(name = "ledger-later", maxAttempts = 2, delay = "200ms", backoff = Backoff.FIXED, jitter = Jitter.NONE)
    @Timeout("2s")
    public CompletionStage<String> postLater(String entry) {
        return asyncAttempts.incrementAndGet() % 2 == 1 ? CompletableFuture.failedFuture(new IllegalStateException("first"))
                : CompletableFuture.completedFuture("posted " + entry);
    }
}
