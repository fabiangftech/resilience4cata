package dev.resilience4cata.spring.scenarios;

import dev.resilience4cata.annotation.CircuitBreaker;
import org.springframework.stereotype.Component;

/** Shares the breaker {@code shared-ledger} with {@link SecondLedger}. */
@Component
public class FirstLedger {

    public volatile boolean failing;

    @CircuitBreaker(name = "shared-ledger", windowSize = 2, minimumCalls = 2, failureRateThreshold = 50,
            waitInOpen = "1h")
    public String post(String entry) {
        if (failing) {
            throw new IllegalStateException("down");
        }
        return "first " + entry;
    }
}
