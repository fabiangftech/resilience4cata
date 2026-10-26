package dev.resilience4cata.quarkus.deployment.fixtures;

import dev.resilience4cata.annotation.Bulkhead;
import dev.resilience4cata.annotation.CircuitBreaker;
import jakarta.inject.Singleton;

/** Owns the breaker "devui" and the bulkhead "devui-bulkhead"; {@link DevUiWarmup} creates it at startup. */
@Singleton
public class DevUiService {

    /**
     * A protected call.
     *
     * @return "ok"
     */
    @CircuitBreaker(name = "devui")
    @Bulkhead(name = "devui-bulkhead", maxConcurrent = 3)
    public String call() {
        return "ok";
    }
}
