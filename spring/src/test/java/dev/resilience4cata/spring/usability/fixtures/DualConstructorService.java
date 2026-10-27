package dev.resilience4cata.spring.usability.fixtures;

import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.spring.fixtures.Money;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Service;

/**
 * Two constructors and no {@code @Autowired}: Spring uses the no-argument one (the other is, say, for tests).
 */
@Service
public class DualConstructorService {

    public final AtomicInteger attempts = new AtomicInteger();
    public final AtomicInteger failures = new AtomicInteger();
    private final String currency;

    public DualConstructorService() {
        this.currency = "default";
    }

    public DualConstructorService(Money money) {
        this.currency = money.currency();
    }

    @Retry(maxAttempts = 3, delay = "10ms")
    public String currency() {
        attempts.incrementAndGet();
        if (failures.getAndDecrement() > 0) {
            throw new IllegalStateException("down");
        }
        return currency;
    }
}
