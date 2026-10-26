package dev.resilience4cata.micronaut;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.micronaut.fixtures.Catalog;
import dev.resilience4cata.micronaut.fixtures.InventoryService;
import dev.resilience4cata.micronaut.fixtures.Payments;
import io.micronaut.context.ApplicationContext;
import java.lang.management.ManagementFactory;
import org.junit.jupiter.api.Test;

/**
 * Micronaut adds nothing to the call path: a successful call on a bean the container created (the generated
 * subclass that replaces an annotated class, one made by a factory, or the generated wrapper of a
 * {@code @ResilientBean}) allocates nothing once compiled.
 */
class AllocationTest {

    /** Bytes a round may allocate: the measurement itself can allocate a little. */
    private static final long LIMIT = 1024;
    private static final int MAX_ROUNDS = 20;

    @Test
    void successfulCallsOnMicronautBeansDoNotAllocate() {
        try (ApplicationContext context = ApplicationContext.run()) {
            InventoryService inventory = context.getBean(InventoryService.class);
            Catalog catalog = context.getBean(Catalog.class);
            Payments payments = context.getBean(Payments.class);
            long bytes = steadyState(() -> {
                long sum = 0;
                for (int i = 0; i < 100_000; i++) {
                    sum += inventory.stock("sku") + catalog.describe("sku").length() + payments.charge(i);
                }
                assertThat(sum).isPositive();
            });
            assertThat(bytes).as("bytes allocated by 100k calls of each bean").isLessThan(LIMIT);
        }
    }

    /** The fewest bytes one batch allocated on this thread, measured until a batch stays under the limit. */
    private static long steadyState(Runnable batch) {
        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().threadId();
        long best = Long.MAX_VALUE;
        for (int round = 0; round < MAX_ROUNDS && best >= LIMIT; round++) {
            long before = threads.getThreadAllocatedBytes(thread);
            batch.run();
            best = Math.min(best, threads.getThreadAllocatedBytes(thread) - before);
        }
        return best;
    }
}
