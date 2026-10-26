package dev.resilience4cata.spring.fixtures;

import dev.resilience4cata.annotation.Retry;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * An annotated class with two constructors, one marked {@code @Autowired}, a qualified parameter and a
 * {@code @Value} parameter: Spring instantiates {@code PricingService$Resilient} through the mirrored
 * constructor, which carries the same annotations.
 */
@Service
public class PricingService {

    public final AtomicInteger attempts = new AtomicInteger();
    public final AtomicInteger failures = new AtomicInteger();
    private final Money money;
    private final int margin;

    public PricingService() {
        this(new Money("none"), 0);
    }

    @Autowired
    public PricingService(@Qualifier("euro") Money money, @Value("${pricing.margin:5}") int margin) {
        this.money = money;
        this.margin = margin;
    }

    @Retry(maxAttempts = 3, delay = "50ms")
    public int price(String sku) {
        attempts.incrementAndGet();
        if (failures.getAndDecrement() > 0) {
            throw new IllegalStateException("pricing down");
        }
        return 100 + margin;
    }

    /** Calls {@link #price} on {@code this}: the generated subclass intercepts it. */
    public int twice(String sku) {
        return price(sku) + price(sku);
    }

    public String currency() {
        return money.currency();
    }
}
