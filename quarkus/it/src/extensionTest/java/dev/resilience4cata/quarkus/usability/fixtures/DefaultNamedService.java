package dev.resilience4cata.quarkus.usability.fixtures;

import dev.resilience4cata.annotation.Retry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;

/** {@code @Named} without a value: CDI names the bean after the class, {@code defaultNamedService}. */
@ApplicationScoped
@Named
public class DefaultNamedService {

    private final Flaky flaky = new Flaky();

    @Retry(maxAttempts = 3, delay = "0ms")
    public String hello() {
        flaky.call();
        return "hello";
    }

    public void failNext(int count) {
        flaky.failNext(count);
    }

    public int calls() {
        return flaky.calls();
    }
}
