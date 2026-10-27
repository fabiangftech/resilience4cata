package dev.resilience4cata.spring.usability.fixtures;

import dev.resilience4cata.annotation.Retry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/** {@code @Async} and {@code @Retry} on one method returning a {@code CompletableFuture}. */
@Service
public class AsyncMailer {

    public final AtomicInteger attempts = new AtomicInteger();
    public final AtomicInteger failures = new AtomicInteger();

    @Async
    @Retry(maxAttempts = 3, delay = "0ms")
    public CompletableFuture<String> send(String to) {
        attempts.incrementAndGet();
        if (failures.getAndDecrement() > 0) {
            return CompletableFuture.failedFuture(new IllegalStateException("smtp down"));
        }
        return CompletableFuture.completedFuture("sent to " + to);
    }
}
