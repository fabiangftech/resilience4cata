package dev.resilience4cata.quarkus.usability.fixtures;

import dev.resilience4cata.annotation.Retry;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.concurrent.atomic.AtomicInteger;

/** One method whose annotation is locked against external overrides, one whose annotation is not. */
@ApplicationScoped
public class LockedClient {

    private final AtomicInteger lockedAttempts = new AtomicInteger();
    private final AtomicInteger openAttempts = new AtomicInteger();

    @Retry(name = "locked-client", maxAttempts = 2, delay = "0ms", locked = true)
    public String locked() {
        lockedAttempts.incrementAndGet();
        throw new IllegalStateException("down");
    }

    @Retry(name = "open-client", maxAttempts = 2, delay = "0ms")
    public String open() {
        openAttempts.incrementAndGet();
        throw new IllegalStateException("down");
    }

    public int lockedAttempts() {
        return lockedAttempts.get();
    }

    public int openAttempts() {
        return openAttempts.get();
    }
}
