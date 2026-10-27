package dev.resilience4cata.quarkus.usability.fixtures;

import dev.resilience4cata.annotation.Retry;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.concurrent.CompletionStage;

/**
 * A reactive Quarkus bean, written the way R4C-ASY-002 asks: the protected method returns a {@code CompletionStage}
 * (the Mutiny pipeline subscribed with {@code subscribeAsCompletionStage()}), so each attempt runs the work. A
 * {@code @Retry} on a method returning {@code Uni} is a compile error.
 */
@ApplicationScoped
public class MutinyQuotes {

    private final Flaky flaky = new Flaky();

    @Retry(maxAttempts = 3, delay = "0ms")
    public CompletionStage<String> quote(String symbol) {
        return Uni.createFrom().item(() -> {
            flaky.call();
            return symbol + "=42";
        }).subscribeAsCompletionStage();
    }

    public void failNext(int count) {
        flaky.failNext(count);
    }

    public int calls() {
        return flaky.calls();
    }
}
