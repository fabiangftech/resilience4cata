package dev.resilience4cata.spring.usability.fixtures;

import dev.resilience4cata.annotation.CircuitBreaker;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/** A {@code @Lazy} protected bean: its policy instance exists only once the bean is first used. */
@Lazy
@Service
public class LazyPayments {

    @CircuitBreaker(name = "lazy-payments")
    public String pay(String order) {
        return "paid " + order;
    }
}
