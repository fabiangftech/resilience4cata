package dev.resilience4cata.benchmark;

import dev.resilience4cata.annotation.Retry;

/** The protected interface. Both methods use the same named config as the Resilience4j retry. */
public interface Service {

    @Retry(config = "bench")
    String call();

    @Retry(config = "bench")
    String failOnce();
}
