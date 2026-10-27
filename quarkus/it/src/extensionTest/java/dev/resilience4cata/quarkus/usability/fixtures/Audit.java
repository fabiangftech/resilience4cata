package dev.resilience4cata.quarkus.usability.fixtures;

import dev.resilience4cata.annotation.Retry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/** An observer method protected by a retry. */
@ApplicationScoped
public class Audit {

    private final Flaky flaky = new Flaky();

    @Retry(maxAttempts = 3, delay = "0ms")
    public void onPing(@Observes Ping ping) {
        flaky.call();
    }

    public void failNext(int count) {
        flaky.failNext(count);
    }

    public int calls() {
        return flaky.calls();
    }

    /** The event. */
    public record Ping(String from) {
    }
}
