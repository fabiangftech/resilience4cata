package dev.resilience4cata.spring.httpservice;

import dev.resilience4cata.CallNotPermittedException;
import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.Fallback;
import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.annotation.Timeout;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

/**
 * A declarative HTTP client: Spring creates its implementation (a JDK proxy over a {@code RestClient}) and the
 * integration wraps that proxy in the generated class. 5xx answers and I/O errors are retried and count for the
 * breaker; 4xx answers are neither; the fallbacks are default methods, called through Spring's proxy.
 */
@HttpExchange("/prices")
public interface PriceService {

    @GetExchange("/{sku}")
    @Retry(maxAttempts = 3, delay = "0", retryOn = {HttpServerErrorException.class, ResourceAccessException.class})
    @CircuitBreaker(windowSize = 4, minimumCalls = 4, failureRateThreshold = 50, waitInOpen = "1h",
            recordOn = {HttpServerErrorException.class, ResourceAccessException.class})
    @Timeout("3s") // generous: the first request starts a cold client and server
    @Fallback(method = "fallback")
    String price(@PathVariable String sku);

    /** The backend could not be reached, even after the retries. */
    default String fallback(String sku, ResourceAccessException unreachable) {
        return "unknown";
    }

    /** The breaker is open. */
    default String fallback(String sku, CallNotPermittedException open) {
        return "breaker open";
    }
}
