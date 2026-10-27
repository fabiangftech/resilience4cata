package dev.resilience4cata.spring.usability.fixtures;

import dev.resilience4cata.annotation.Retry;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/** An annotated class whose retried method is also {@code @Cacheable} with a SpEL key naming its parameter. */
@Service
public class CachedQuotes {

    private final AtomicInteger calls = new AtomicInteger();

    @Retry(maxAttempts = 3, delay = "10ms")
    @Cacheable(cacheNames = "quotes", key = "#sku")
    public int quote(String sku) {
        return 100 + calls.incrementAndGet();
    }

    /** Through a method: the bean is a class-based proxy ({@code @Cacheable}), whose own fields are not set. */
    public int calls() {
        return calls.get();
    }
}
