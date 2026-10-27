package dev.resilience4cata.micronaut.usability;

import dev.resilience4cata.annotation.Retry;
import io.micronaut.context.annotation.EachBean;
import io.micronaut.context.annotation.Requires;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The guide's pattern for policies per configured entry: the configuration in its own {@code @EachProperty} class,
 * the policies on an {@code @EachBean} of it (an annotated class that is itself a configuration class makes Micronaut
 * warn about writing its configuration metadata twice).
 */
@EachBean(BackendConfig.class)
@Requires(property = "test.usability", value = "each-bean")
public class Backend {

    private final BackendConfig config;
    private final AtomicInteger failures = new AtomicInteger();
    private final AtomicInteger calls = new AtomicInteger();

    public Backend(BackendConfig config) {
        this.config = config;
    }

    @Retry(maxAttempts = 3, delay = "0ms")
    public String call(String path) {
        calls.incrementAndGet();
        if (failures.getAndDecrement() > 0) {
            throw new IllegalStateException("backend " + config.getName() + " is down");
        }
        return config.getUrl() + path;
    }

    public String name() {
        return config.getName();
    }

    public void failNext(int count) {
        failures.set(count);
    }

    public int calls() {
        return calls.get();
    }
}
