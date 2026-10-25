package dev.resilience4cata.examples.docs.fallback;

import dev.resilience4cata.annotation.Fallback;
import java.util.concurrent.atomic.AtomicInteger;

/** A {@code void} method with a {@code void} sibling fallback. */
public interface RefreshApi {

    AtomicInteger SKIPPED = new AtomicInteger();

    @Fallback(method = "skip")
    void refresh(String id);

    default void skip(String id, RuntimeException failure) {
        SKIPPED.incrementAndGet();
    }
}
