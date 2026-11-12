package com.example.consumer;

import dev.resilience4cata.annotation.Bulkhead;
import dev.resilience4cata.annotation.Retry;
import java.io.IOException;

/** An annotated class, created through its generated subclass with {@code Resilient.create}. */
public class Warehouse {

    private final String name;
    private int calls;

    public Warehouse(String name) {
        this.name = name;
    }

    @Retry(maxAttempts = 3, delay = "1ms")
    @Bulkhead(maxConcurrent = 1)
    public String reserve(String sku) throws IOException {
        if (++calls < 3) {
            throw new IOException("busy");
        }
        return name + ":" + sku;
    }

    public int calls() {
        return calls;
    }
}
