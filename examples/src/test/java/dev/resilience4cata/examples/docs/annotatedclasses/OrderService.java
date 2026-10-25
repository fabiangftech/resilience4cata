package dev.resilience4cata.examples.docs.annotatedclasses;

import dev.resilience4cata.annotation.Fallback;
import dev.resilience4cata.annotation.Retry;
import java.util.concurrent.atomic.AtomicInteger;

/** The class level of the precedence example: its annotations win attribute by attribute. */
// tag::annotated-classes-order-service[]
public class OrderService implements OrderApi {
    final AtomicInteger calls = new AtomicInteger();

    @Override
    @Retry(maxAttempts = 4)   // wins over the interface method's 2; the delay still comes from OrderApi
    public String status(String id) {
        calls.incrementAndGet();
        throw new IllegalStateException("orders down");
    }

    @Override                 // not annotated here: OrderApi's three attempts apply
    public String quote(String id) {
        calls.incrementAndGet();
        throw new IllegalStateException("quotes down");
    }

    @Fallback(method = "cachedPrice")
    public String price(String id) {
        throw new IllegalStateException("pricing down");
    }

    protected String cachedPrice(String id, IllegalStateException e) {   // not private: the subclass calls it
        return "cached price of " + id;
    }
}
// end::annotated-classes-order-service[]
