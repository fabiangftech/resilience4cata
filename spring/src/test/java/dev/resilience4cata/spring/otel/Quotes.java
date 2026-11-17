package dev.resilience4cata.spring.otel;

import dev.resilience4cata.annotation.CircuitBreaker;

/** An annotated class whose breaker the OpenTelemetry test opens. */
public class Quotes {

    @CircuitBreaker(name = "quotes")
    public String quote(String symbol) {
        return symbol + ":42";
    }
}
