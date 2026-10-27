package dev.resilience4cata.quarkus.usability.fixtures;

import dev.resilience4cata.annotation.Retry;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

/** A reactive Quarkus bean: its method returns a Mutiny {@code Uni}, which runs (and fails) when subscribed. */
@ApplicationScoped
public class MutinyQuotes {

    private final Flaky flaky = new Flaky();

    @Retry(maxAttempts = 3, delay = "0ms")
    public Uni<String> quote(String symbol) {
        return Uni.createFrom().item(() -> {
            flaky.call();
            return symbol + "=42";
        });
    }

    public void failNext(int count) {
        flaky.failNext(count);
    }

    public int calls() {
        return flaky.calls();
    }
}
