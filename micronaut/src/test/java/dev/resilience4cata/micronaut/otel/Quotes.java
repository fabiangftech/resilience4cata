package dev.resilience4cata.micronaut.otel;

import dev.resilience4cata.annotation.CircuitBreaker;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

/** An annotated class whose breaker the OpenTelemetry test opens. */
@Singleton
@Requires(property = "test.opentelemetry")
public class Quotes {

    @CircuitBreaker(name = "quotes")
    public String quote(String symbol) {
        return symbol + ":42";
    }
}
