package dev.resilience4cata.spring.fixtures;

import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.annotation.Timeout;
import org.springframework.stereotype.Component;

/** Policies tuned from the application's properties: a named retry and a timeout with a derived id. */
@Component
public class ShippingService {

    @Retry(name = "shipping")
    public String ship(String order) {
        return "shipped " + order;
    }

    @Timeout
    public String track(String order) {
        return "on its way: " + order;
    }
}
