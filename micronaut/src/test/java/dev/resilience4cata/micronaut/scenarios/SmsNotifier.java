package dev.resilience4cata.micronaut.scenarios;

import dev.resilience4cata.annotation.Retry;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.util.concurrent.atomic.AtomicInteger;

/** An annotated class qualified by {@code @Named("sms")}. */
@Singleton
@Named("sms")
@Requires(property = "test.scenarios", value = "qualifiers")
public class SmsNotifier implements Notifier {

    public final AtomicInteger attempts = new AtomicInteger();
    public final AtomicInteger failures = new AtomicInteger();

    @Override
    @Retry(maxAttempts = 3, delay = "10ms")
    public String send(String message) {
        attempts.incrementAndGet();
        if (failures.getAndDecrement() > 0) {
            throw new IllegalStateException("gateway down");
        }
        return "sms " + message;
    }
}
