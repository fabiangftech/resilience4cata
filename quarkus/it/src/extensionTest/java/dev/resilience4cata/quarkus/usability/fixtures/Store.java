package dev.resilience4cata.quarkus.usability.fixtures;

import dev.resilience4cata.annotation.Retry;

/** A generic annotated interface, such as a repository. */
@Retry(maxAttempts = 3, delay = "0ms")
public interface Store<T> {

    T load(String id);
}
