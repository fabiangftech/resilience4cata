package dev.resilience4cata.spring.scenarios;

import dev.resilience4cata.annotation.Retry;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** An annotated class qualified by {@code @Qualifier("sms")}. */
@Component
@Qualifier("sms")
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
