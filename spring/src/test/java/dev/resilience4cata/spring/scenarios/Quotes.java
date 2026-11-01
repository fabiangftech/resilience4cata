package dev.resilience4cata.spring.scenarios;

import dev.resilience4cata.annotation.Retry;

/** An annotated interface with two implementations: a {@code @Primary} one and a qualified one. */
@Retry(maxAttempts = 3, delay = "10ms")
public interface Quotes {

    String quote(String sku);
}
