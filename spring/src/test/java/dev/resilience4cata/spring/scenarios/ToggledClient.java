package dev.resilience4cata.spring.scenarios;

import dev.resilience4cata.annotation.Retry;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** An annotated class that exists only when a property says so. */
@Component
@ConditionalOnProperty("scenarios.toggled")
public class ToggledClient {

    public final AtomicInteger attempts = new AtomicInteger();

    @Retry(maxAttempts = 2, delay = "10ms")
    public String call() {
        if (attempts.incrementAndGet() == 1) {
            throw new IllegalStateException("first");
        }
        return "ok";
    }
}
