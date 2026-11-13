package pilot.quarkus;

import dev.resilience4cata.BulkheadFullException;
import dev.resilience4cata.annotation.Bulkhead;
import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.Fallback;
import dev.resilience4cata.annotation.RateLimiter;
import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.annotation.Timeout;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import pilot.common.BackendClient;
import pilot.common.Probe;

/**
 * The protected calls; the same class in the three services. {@code stock} has every sync policy, {@code quote} a
 * fail-fast rate limiter.
 */
@ApplicationScoped
public class Inventory {

    private final BackendClient backend = new BackendClient();

    @Fallback(method = "lastKnown")
    @Retry(name = "inventory", maxAttempts = 3, delay = "20ms")
    @CircuitBreaker(name = "inventory", windowSize = 100, minimumCalls = 20, failureRateThreshold = 50,
            waitInOpen = "5s", halfOpenCalls = 5,
            // As the circuit breaker page advises: our own full bulkhead is not the backend failing. Without it, the
            // cold start's queue (Spring, first seconds) opened the breaker with no backend failure at all.
            ignoreOn = BulkheadFullException.class)
    @Timeout("200ms")
    @Bulkhead(name = "inventory", maxConcurrent = 40, maxWait = "20ms")
    public String stock(String id) throws IOException, InterruptedException {
        return Probe.STOCK.call(() -> backend.get("/stock/" + id));
    }

    /** The answer when the backend cannot give one: says why, so the load test can count each reason. */
    String lastKnown(String id, Throwable failure) {
        return "fallback:" + failure.getClass().getSimpleName();
    }

    @RateLimiter(name = "quotes", permits = 200, period = "1s")
    public String quote(String id) throws IOException, InterruptedException {
        return Probe.QUOTE.call(() -> backend.get("/quote/" + id));
    }
}
