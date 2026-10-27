package dev.resilience4cata.quarkus.usability.fixtures;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class EmailNotifier implements Notifier {

    private final Flaky flaky = new Flaky();

    @Override
    public String send(String message) {
        flaky.call();
        return "email: " + message;
    }

    public void failNext(int count) {
        flaky.failNext(count);
    }
}
