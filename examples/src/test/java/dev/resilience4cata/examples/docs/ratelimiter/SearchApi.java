package dev.resilience4cata.examples.docs.ratelimiter;

// tag::rate-limiter-minimal[]
import dev.resilience4cata.annotation.RateLimiter;

public interface SearchApi {

    @RateLimiter(permits = 10, period = "1s")
    String search(String query);
}
// end::rate-limiter-minimal[]
