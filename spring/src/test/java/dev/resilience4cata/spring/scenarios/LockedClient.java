package dev.resilience4cata.spring.scenarios;

import dev.resilience4cata.annotation.Retry;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/** One method whose annotation is locked against external overrides, one whose annotation is not. */
@Component
public class LockedClient {

    public final AtomicInteger lockedAttempts = new AtomicInteger();
    public final AtomicInteger openAttempts = new AtomicInteger();

    @Retry(name = "locked-client", maxAttempts = 2, delay = "10ms", locked = true)
    public String locked() {
        lockedAttempts.incrementAndGet();
        throw new IllegalStateException("down");
    }

    @Retry(name = "open-client", maxAttempts = 2, delay = "10ms")
    public String open() {
        openAttempts.incrementAndGet();
        throw new IllegalStateException("down");
    }
}
