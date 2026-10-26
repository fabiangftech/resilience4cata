package dev.resilience4cata.micronaut.fixtures;

import dev.resilience4cata.annotation.Retry;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

/**
 * An annotated class with two constructors, one marked {@code @Inject}, a qualified parameter and a {@code @Value}
 * parameter: Micronaut creates {@code PricingService$Resilient} through the mirrored constructor, which carries the
 * same annotations.
 */
@Singleton
@Named("pricing")
public class PricingService {

    private final Outage outage;
    private final Money money;
    private final int margin;

    public PricingService() {
        this(new Outage(), new Money("none"), 0);
    }

    @Inject
    public PricingService(Outage outage, @Named("euro") Money money, @Value("${pricing.margin:5}") int margin) {
        this.outage = outage;
        this.money = money;
        this.margin = margin;
    }

    @Retry(maxAttempts = 3, delay = "50ms")
    public int price(String sku) {
        outage.call();
        return 100 + margin;
    }

    public String currency() {
        return money.currency();
    }
}
