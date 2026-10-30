package dev.resilience4cata.spring.proxies;

import dev.resilience4cata.annotation.Retry;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.scheduling.annotation.Scheduled;

/** A scheduled, retried method of an annotated class: the scheduler invokes the generated override. */
public class ScheduledSync {

    public final AtomicInteger runs = new AtomicInteger();
    private final Backend backend;

    public ScheduledSync(Backend backend) {
        this.backend = backend;
    }

    @Scheduled(fixedDelay = 50)
    @Retry(maxAttempts = 3, delay = "1ms")
    public void sync() {
        backend.call("sync");
        runs.incrementAndGet();
    }
}
