package dev.resilience4cata.examples.docs.testing;

import dev.resilience4cata.annotation.RateLimiter;

// tag::testing-rate-limiter-interface[]
public interface SearchService {

    @RateLimiter(name = "search", permits = 2, period = "1s")
    String search(String query);

    @RateLimiter(name = "export", permits = 1, period = "1s", maxWait = "2s")
    String export(String query);
}
// end::testing-rate-limiter-interface[]
