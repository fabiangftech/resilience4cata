package dev.resilience4cata.spring.usability.fixtures;

import dev.resilience4cata.annotation.Retry;

/** A generic annotated interface with several implementations, told apart by their type argument. */
@Retry(maxAttempts = 3, delay = "10ms")
public interface Lookup<T> {

    T find(String id);
}
