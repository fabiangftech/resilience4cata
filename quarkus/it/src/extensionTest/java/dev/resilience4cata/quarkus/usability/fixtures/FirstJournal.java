package dev.resilience4cata.quarkus.usability.fixtures;

import dev.resilience4cata.annotation.CircuitBreaker;
import jakarta.enterprise.context.ApplicationScoped;

/** Shares the breaker {@code shared-journal} with {@link SecondJournal}. */
@ApplicationScoped
public class FirstJournal {

    private volatile boolean failing;

    @CircuitBreaker(name = "shared-journal", windowSize = 2, minimumCalls = 2, failureRateThreshold = 50,
            waitInOpen = "1h")
    public String post(String entry) {
        if (failing) {
            throw new IllegalStateException("down");
        }
        return "first " + entry;
    }

    public void failing(boolean value) {
        failing = value;
    }
}
