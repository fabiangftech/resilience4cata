package dev.resilience4cata.examples.docs.pipeline;

import dev.resilience4cata.annotation.CircuitBreaker;

/** An annotation whose name is a pipeline id: both share one breaker. */
public interface SearchApi {

    @CircuitBreaker(name = "search")
    String find(String query);
}
