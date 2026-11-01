package dev.resilience4cata.spring.scenarios;

import dev.resilience4cata.annotation.Retry;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * Calls one of its own protected methods from {@code afterPropertiesSet} (Spring runs {@code @PostConstruct}
 * methods at the same point): it runs after the generated constructor has set the policy fields, so the call is
 * retried.
 */
@Component
public class WarmedCache implements InitializingBean {

    public final AtomicInteger attempts = new AtomicInteger();
    public volatile String warmed;

    @Override
    public void afterPropertiesSet() {
        warmed = load("warm-up");
    }

    /** Fails every odd attempt. */
    @Retry(name = "warmed-cache", maxAttempts = 2, delay = "10ms")
    public String load(String key) {
        if (attempts.incrementAndGet() % 2 == 1) {
            throw new IllegalStateException("cold");
        }
        return "loaded " + key;
    }
}
