package dev.resilience4cata.quarkus.usability.fixtures;

import dev.resilience4cata.annotation.Retry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The fix the guide gives for R4C-QK-005: an {@code @ApplicationScoped} class with constructor injection and a
 * package-private constructor without parameters, for the client proxy.
 */
@ApplicationScoped
public class Ledger {

    private final Clerk clerk;
    private final Flaky flaky = new Flaky();

    Ledger() {
        this.clerk = null;
    }

    @Inject
    public Ledger(Clerk clerk) {
        this.clerk = clerk;
    }

    @Retry(maxAttempts = 3, delay = "0ms")
    public String post(String entry) {
        flaky.call();
        return clerk.sign(entry);
    }

    public void failNext(int count) {
        flaky.failNext(count);
    }

    public int calls() {
        return flaky.calls();
    }
}
