package dev.resilience4cata.quarkus.usability.fixtures;

import jakarta.enterprise.context.ApplicationScoped;

/** An implementation of an annotated interface whose method has an interceptor binding. */
@ApplicationScoped
@Premium
public class CountedSmsNotifier implements Notifier {

    private final Flaky flaky = new Flaky();

    @Override
    @Counted
    public String send(String message) {
        flaky.call();
        return "premium sms: " + message;
    }

    public void failNext(int count) {
        flaky.failNext(count);
    }

    public int calls() {
        return flaky.calls();
    }
}
