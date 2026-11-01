package dev.resilience4cata.spring.scenarios;

import dev.resilience4cata.annotation.Retry;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/** An annotated class qualified by a custom qualifier annotation. */
@Component
@Email
public class EmailNotifier implements Notifier {

    public final AtomicInteger attempts = new AtomicInteger();
    public final AtomicInteger failures = new AtomicInteger();

    @Override
    @Retry(maxAttempts = 3, delay = "10ms")
    public String send(String message) {
        attempts.incrementAndGet();
        if (failures.getAndDecrement() > 0) {
            throw new IllegalStateException("smtp down");
        }
        return "email " + message;
    }
}
