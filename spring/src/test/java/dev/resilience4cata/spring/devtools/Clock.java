package dev.resilience4cata.spring.devtools;

import dev.resilience4cata.annotation.Retry;

/** An annotated interface: its implementation bean is wrapped with {@code Clock$Resilient}. */
public interface Clock {

    @Retry(maxAttempts = 3)
    String now();
}
