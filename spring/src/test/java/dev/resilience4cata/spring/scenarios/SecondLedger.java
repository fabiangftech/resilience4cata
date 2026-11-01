package dev.resilience4cata.spring.scenarios;

import dev.resilience4cata.annotation.CircuitBreaker;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/** Shares the breaker {@code shared-ledger} with {@link FirstLedger}. */
@Component
public class SecondLedger {

    public final AtomicInteger calls = new AtomicInteger();

    @CircuitBreaker(name = "shared-ledger", windowSize = 2, minimumCalls = 2, failureRateThreshold = 50,
            waitInOpen = "1h")
    public String post(String entry) {
        calls.incrementAndGet();
        return "second " + entry;
    }
}
