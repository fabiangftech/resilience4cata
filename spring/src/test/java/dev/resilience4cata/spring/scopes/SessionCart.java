package dev.resilience4cata.spring.scopes;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.SessionScope;

/** One cart per HTTP session; its state lives as long as the session. */
@Component
@SessionScope
public class SessionCart implements CartClient {

    private static final AtomicInteger INSTANCES = new AtomicInteger();

    private final String id = "cart-" + INSTANCES.incrementAndGet();
    private final List<String> items = new CopyOnWriteArrayList<>();
    private final Attempts attempts;

    public SessionCart(Attempts attempts) {
        this.attempts = attempts;
    }

    @Override
    public int add(String item) {
        attempts.attempt(id);
        items.add(item);
        return items.size();
    }

    @Override
    public List<String> items() {
        return List.copyOf(items);
    }
}
