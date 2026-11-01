package dev.resilience4cata.quarkus.usability.fixtures;

import dev.resilience4cata.annotation.CircuitBreaker;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.concurrent.atomic.AtomicInteger;

/** Shares the breaker {@code shared-journal} with {@link FirstJournal}. */
@ApplicationScoped
public class SecondJournal {

    private final AtomicInteger calls = new AtomicInteger();

    @CircuitBreaker(name = "shared-journal", windowSize = 2, minimumCalls = 2, failureRateThreshold = 50,
            waitInOpen = "1h")
    public String post(String entry) {
        calls.incrementAndGet();
        return "second " + entry;
    }

    public int calls() {
        return calls.get();
    }
}
