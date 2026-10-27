package dev.resilience4cata.micronaut.usability;

import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.micronaut.ResilientBean;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.client.annotation.Client;

/** A declarative HTTP client, the most common remote call in Micronaut, protected through its interface. */
@Client("/")
@ResilientBean(GreetingClient.class)
@Requires(property = "test.usability", value = "http")
public interface GreetingClient {

    @Get("/usability/greeting")
    @Retry(maxAttempts = 3)
    String greeting();
}
