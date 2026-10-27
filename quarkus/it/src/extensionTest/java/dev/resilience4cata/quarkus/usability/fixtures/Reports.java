package dev.resilience4cata.quarkus.usability.fixtures;

import dev.resilience4cata.annotation.Retry;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;

/** A scheduled job that retries: {@code @Scheduled} on a method of an annotated class. */
@ApplicationScoped
public class Reports {

    private final Flaky flaky = new Flaky();

    @Retry(maxAttempts = 3, delay = "0ms")
    @Scheduled(every = "1h", delayed = "1h")
    public void refresh() {
        flaky.call();
    }

    public int calls() {
        return flaky.calls();
    }
}
