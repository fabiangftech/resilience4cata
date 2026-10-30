package dev.resilience4cata.quarkus.deployment.fixtures;

import dev.resilience4cata.annotation.Backoff;
import dev.resilience4cata.annotation.Fallback;
import dev.resilience4cata.annotation.Jitter;
import dev.resilience4cata.annotation.Retry;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

/** An annotated class that dev mode recompiles in ReloadTest: its retry's maxAttempts is edited. */
@ApplicationScoped
public class ReloadService {

    private final AtomicInteger attempts = new AtomicInteger();
    private final AtomicInteger asyncAttempts = new AtomicInteger();

    /** Always fails: the fallback reports how many attempts the retry made. */
    @Retry(maxAttempts = 2, delay = "1ms", backoff = Backoff.FIXED, jitter = Jitter.NONE)
    @Fallback(method = "attemptsMade")
    public String attempts() {
        attempts.incrementAndGet();
        throw new IllegalStateException("always");
    }

    String attemptsMade(Throwable failure) {
        return String.valueOf(attempts.getAndSet(0));
    }

    /**
     * Fails its first attempt; the second runs after the retry's delay, started from the library's timer, and reports
     * whether its context class loader is this application's (the one that loaded this class).
     */
    @Retry(maxAttempts = 2, delay = "10ms", backoff = Backoff.FIXED, jitter = Jitter.NONE)
    public CompletionStage<String> loaderOfALaterAttempt() {
        if (asyncAttempts.incrementAndGet() % 2 == 1) {
            return CompletableFuture.failedFuture(new IllegalStateException("first attempt"));
        }
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        return CompletableFuture.completedFuture(context == ReloadService.class.getClassLoader() ? "the application's"
                : "another: " + context);
    }
}
