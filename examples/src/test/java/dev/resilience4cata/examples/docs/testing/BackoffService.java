package dev.resilience4cata.examples.docs.testing;

import dev.resilience4cata.annotation.Jitter;
import dev.resilience4cata.annotation.Retry;

// tag::testing-retry-interface[]
public interface BackoffService {

    // exponential backoff (the default): 1 s, 2 s, then capped at 3 s
    @Retry(maxAttempts = 4, delay = "1s", maxDelay = "3s", jitter = Jitter.NONE)
    String load(String id);

    // the default FULL jitter: each delay is random between 0 and the backoff
    @Retry(maxAttempts = 4, delay = "1s")
    String loadJittered(String id);
}
// end::testing-retry-interface[]
