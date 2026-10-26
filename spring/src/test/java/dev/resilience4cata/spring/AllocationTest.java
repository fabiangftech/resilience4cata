package dev.resilience4cata.spring;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.spring.fixtures.InventoryClient;
import dev.resilience4cata.spring.fixtures.InventoryClientImpl;
import dev.resilience4cata.spring.fixtures.PricingService;
import java.lang.management.ManagementFactory;
import org.junit.jupiter.api.Test;

/**
 * Spring adds nothing to the call path: a successful call on a bean the container created (the generated
 * subclass, or the generated wrapper of an interface bean) allocates nothing once compiled.
 */
class AllocationTest {

    /** Bytes a round may allocate: the measurement itself can allocate a little. */
    private static final long LIMIT = 1024;
    private static final int MAX_ROUNDS = 20;

    @Test
    void successfulCallsOnSpringBeansDoNotAllocate() {
        Runners.runner().withUserConfiguration(PricingService.class, InventoryClientImpl.class).run(context -> {
            PricingService pricing = context.getBean(PricingService.class);
            InventoryClient inventory = context.getBean(InventoryClient.class);
            long bytes = steadyState(() -> {
                long sum = 0;
                for (int i = 0; i < 100_000; i++) {
                    sum += pricing.price("sku") + inventory.stock("sku");
                }
                assertThat(sum).isPositive();
            });
            assertThat(bytes).as("bytes allocated by 100k calls of each bean").isLessThan(LIMIT);
        });
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
