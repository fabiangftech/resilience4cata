package dev.resilience4cata.quarkus.usability.fixtures;

import dev.resilience4cata.annotation.Retry;
import jakarta.enterprise.context.ApplicationScoped;

/** An annotated class with an interceptor binding on its retried method. */
@ApplicationScoped
public class Billing {

    private final Flaky flaky = new Flaky();

    @Counted
    @Retry(maxAttempts = 3, delay = "0ms")
    public String bill(String order) {
        flaky.call();
        return "billed " + order;
    }

    public void failNext(int count) {
        flaky.failNext(count);
    }

    public int calls() {
        return flaky.calls();
    }
}
