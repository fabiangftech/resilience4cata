package dev.resilience4cata.examples.docs.ratelimiter;

import dev.resilience4cata.annotation.RateLimiter;
import java.util.concurrent.CompletableFuture;

/** A shared limiter whose callers may wait for a permit. */
public interface GeocodingApi {

    // tag::rate-limiter-wait[]
    @RateLimiter(name = "geocoding", permits = 5, period = "1s", maxWait = "500ms")
    String geocode(String address);

    @RateLimiter(name = "geocoding", permits = 5, period = "1s", maxWait = "500ms")
    String reverse(double latitude, double longitude);
    // end::rate-limiter-wait[]

    // tag::rate-limiter-async[]
    @RateLimiter(name = "geocoding-async", permits = 5, period = "1s", maxWait = "500ms")
    CompletableFuture<String> geocodeAsync(String address);
    // end::rate-limiter-async[]
}
