package dev.resilience4cata.quarkus.usability.fixtures;

import dev.resilience4cata.annotation.Retry;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

/** A library-style default: used unless the application declares another {@code DefaultTranslator}. */
@ApplicationScoped
@DefaultBean
public class DefaultTranslator {

    private final Flaky flaky = new Flaky();

    @Retry(maxAttempts = 2, delay = "0ms")
    public String translate(String text) {
        flaky.call();
        return "default:" + text;
    }

    public void failNext(int count) {
        flaky.failNext(count);
    }
}
