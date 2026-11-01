package dev.resilience4cata.micronaut.scenarios;

import dev.resilience4cata.annotation.CircuitBreaker;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import java.util.concurrent.atomic.AtomicInteger;

/** Shares the breaker {@code shared-ledger} with {@link FirstLedger}. */
@Singleton
@Requires(property = "test.scenarios", value = "shared")
public class SecondLedger {

    public final AtomicInteger calls = new AtomicInteger();

    @CircuitBreaker(name = "shared-ledger", windowSize = 2, minimumCalls = 2, failureRateThreshold = 50,
            waitInOpen = "1h")
    public String post(String entry) {
        calls.incrementAndGet();
        return "second " + entry;
    }
}
