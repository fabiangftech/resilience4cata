package dev.resilience4cata.examples;

import dev.resilience4cata.annotation.Jitter;
import dev.resilience4cata.annotation.Retry;

/** Generic interfaces are supported; the generated class keeps the type parameter. */
public interface Repository<T> {

    @Retry(maxAttempts = 2, delay = "1ms", jitter = Jitter.NONE)
    T load(String id);

    /** Nested interfaces get a generated class named {@code Repository$Cache$Resilient}. */
    interface Cache {
        @Retry(maxAttempts = 2, delay = "1ms", jitter = Jitter.NONE)
        String get(String key);
    }
}
