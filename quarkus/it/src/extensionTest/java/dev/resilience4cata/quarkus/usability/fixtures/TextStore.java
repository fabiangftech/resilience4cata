package dev.resilience4cata.quarkus.usability.fixtures;

import jakarta.enterprise.context.ApplicationScoped;

/** An implementation of the generic interface for one type argument. */
@ApplicationScoped
public class TextStore implements Store<String> {

    private final Flaky flaky = new Flaky();

    @Override
    public String load(String id) {
        flaky.call();
        return "text-" + id;
    }

    public void failNext(int count) {
        flaky.failNext(count);
    }

    public int calls() {
        return flaky.calls();
    }
}
