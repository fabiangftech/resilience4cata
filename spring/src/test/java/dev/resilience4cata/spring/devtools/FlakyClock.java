package dev.resilience4cata.spring.devtools;

import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/** Fails twice, then answers. */
@Component
public class FlakyClock implements Clock {

    // static: the bean is the wrapper, so it cannot be looked up by this class; every restart loads this class again
    static final AtomicInteger ATTEMPTS = new AtomicInteger();

    @Override
    public String now() {
        if (ATTEMPTS.incrementAndGet() < 3) {
            throw new IllegalStateException("not yet");
        }
        return "noon";
    }
}
