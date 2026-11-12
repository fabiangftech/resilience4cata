package com.example.consumer;

import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import dev.resilience4cata.config.ResilienceConfig;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Uses resilience4cata as installed from its published artifacts (consumer/check.sh): generated code for an
 * interface and a class, an async method, and configuration read from the class path. Fails with an exception, so a
 * non-zero exit, when something does not behave as documented.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        ResilienceConfig config = ResilienceConfig.load();
        ResilienceRegistry registry = config.applyTo(ResilienceRegistry.builder()).build();

        AtomicInteger attempts = new AtomicInteger();
        Inventory down = Resilient.of(Inventory.class, new Inventory() {
            @Override
            public int stock(String sku) throws IOException {
                attempts.incrementAndGet();
                throw new IOException("down");
            }

            @Override
            public CompletableFuture<Integer> stockAsync(String sku) {
                return attempts.incrementAndGet() % 2 == 1
                        ? CompletableFuture.failedFuture(new IOException("down"))
                        : CompletableFuture.completedFuture(7);
            }
        }, registry);
        check(down.stock("a") == -1, "the fallback answers after the retries");
        check(attempts.get() == 4, "resilience4cata.properties sets 4 attempts, got " + attempts.get());

        attempts.set(0);
        int async = down.stockAsync("a").get();
        check(async == 7 && attempts.get() == 2, "the async retry succeeds on attempt 2, got " + attempts.get());

        Warehouse warehouse = Resilient.create(Warehouse.class, registry, "north");
        check(!warehouse.getClass().equals(Warehouse.class), "Resilient.create returns the generated subclass");
        check(warehouse.reserve("b").equals("north:b") && warehouse.calls() == 3, "the class's retry runs 3 attempts");

        check(registry.circuitBreaker("inventory") != null, "the breaker is in the registry");
        System.out.println("consumer OK (" + (Main.class.getModule().isNamed() ? "module path" : "class path") + ")");
    }

    private static void check(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError(what);
        }
    }
}
