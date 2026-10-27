package dev.resilience4cata.quarkus.usability.fixtures;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class SmsNotifier implements Notifier {

    private final Flaky flaky = new Flaky();

    @Override
    public String send(String message) {
        flaky.call();
        return "sms: " + message;
    }

    public void failNext(int count) {
        flaky.failNext(count);
    }
}
