package dev.resilience4cata.quarkus.deployment.fixtures;

import dev.resilience4cata.annotation.Retry;
import jakarta.enterprise.context.RequestScoped;
import java.util.ArrayList;
import java.util.List;

/** A @RequestScoped annotated class: normal-scoped, so it has a no-argument constructor for its client proxy. */
@RequestScoped
public class Cart {

    private final List<String> items = new ArrayList<>();

    @Retry(maxAttempts = 2, delay = "0ms")
    public int add(String item) {
        items.add(item);
        return items.size();
    }
}
