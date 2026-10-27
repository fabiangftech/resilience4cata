package dev.resilience4cata.spring.usability.fixtures;

import dev.resilience4cata.annotation.CircuitBreaker;
import org.springframework.stereotype.Service;

/** A breaker without a name on a two-parameter method: its derived id contains a comma. */
@Service
public class Charges {

    @CircuitBreaker
    public int charge(String account, int cents) {
        return cents;
    }
}
