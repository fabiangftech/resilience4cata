package dev.resilience4cata.spring.tx;

import dev.resilience4cata.annotation.Retry;

/** The interface style: the wrapper goes around the transaction proxy. */
@Retry(maxAttempts = 3, delay = "10ms")
public interface LedgerClient {

    String post(String entry);
}
