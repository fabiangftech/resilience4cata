package dev.resilience4cata.spring.devtools;

import dev.resilience4cata.annotation.Retry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/** An annotated class: the bean is its generated subclass, which must come from the current restart loader. */
@Component
public class Greeter {

    final AtomicInteger attempts = new AtomicInteger();
    volatile ClassLoader secondAttemptLoader;

    /** Fails twice, then answers. */
    @Retry(maxAttempts = 3)
    public String greet() {
        if (attempts.incrementAndGet() < 3) {
            throw new IllegalStateException("not yet");
        }
        return "hello";
    }

    /**
     * Fails once; the retry's second attempt runs later on a thread the library starts, and records that thread's
     * context class loader.
     */
    @Retry(maxAttempts = 2, delay = "10ms")
    public CompletableFuture<String> greetLater() {
        if (attempts.incrementAndGet() == 1) {
            return CompletableFuture.failedFuture(new IllegalStateException("not yet"));
        }
        secondAttemptLoader = Thread.currentThread().getContextClassLoader();
        return CompletableFuture.completedFuture("hello later");
    }
}
