package dev.resilience4cata.examples.docs.testing;

import dev.resilience4cata.annotation.Backoff;
import dev.resilience4cata.annotation.Bulkhead;
import dev.resilience4cata.annotation.Jitter;
import dev.resilience4cata.annotation.Retry;
import java.util.concurrent.CompletableFuture;

// tag::testing-async-interface[]
public interface RenderService {

    @Bulkhead(name = "renderer", maxConcurrent = 1, maxWait = "30s")
    CompletableFuture<String> render(String id);

    @Retry(maxAttempts = 3, delay = "1s", backoff = Backoff.FIXED, jitter = Jitter.NONE)
    CompletableFuture<String> fetch(String id);
}
// end::testing-async-interface[]
