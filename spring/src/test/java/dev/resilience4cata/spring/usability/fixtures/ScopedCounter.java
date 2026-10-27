package dev.resilience4cata.spring.usability.fixtures;

import dev.resilience4cata.annotation.Retry;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.stereotype.Component;

/** An annotated class in a custom scope behind a class-based scoped proxy (as request/session beans are). */
@Component
@Scope(scopeName = "thread", proxyMode = ScopedProxyMode.TARGET_CLASS)
public class ScopedCounter {

    private final AtomicInteger attempts = new AtomicInteger();
    private final AtomicInteger failures = new AtomicInteger();

    public void failNext(int count) {
        failures.set(count);
    }

    public int attempts() {
        return attempts.get();
    }

    @Retry(maxAttempts = 3, delay = "10ms")
    public int next() {
        int attempt = attempts.incrementAndGet();
        if (failures.getAndDecrement() > 0) {
            throw new IllegalStateException("down");
        }
        return attempt;
    }
}
