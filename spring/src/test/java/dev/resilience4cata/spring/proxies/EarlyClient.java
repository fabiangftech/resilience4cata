package dev.resilience4cata.spring.proxies;

import dev.resilience4cata.annotation.Retry;

/** An annotated interface whose implementation a BeanPostProcessor depends on: created before most post-processors. */
public interface EarlyClient {

    @Retry(maxAttempts = 3, delay = "1ms")
    String fetch(String id);
}
