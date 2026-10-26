package dev.resilience4cata.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.lang.management.ManagementFactory;
import org.junit.jupiter.api.Test;

/**
 * Quarkus adds nothing to the call path that allocates: a successful call through the injected beans (the generated
 * subclass behind ArC's client proxy, and the generated wrapper of an interface around the bean's client proxy)
 * allocates nothing once compiled.
 */
@QuarkusTest
class AllocationTest {

    /** Bytes a round may allocate: the measurement itself can allocate a little. */
    private static final long LIMIT = 1024;
    private static final int MAX_ROUNDS = 20;

    @Inject
    Quotes quotes;

    @Inject
    Ratings ratings;

    @Test
    void successfulCallsOnQuarkusBeansDoNotAllocate() {
        assertThat(quotes.getClass().getName()).endsWith("Quotes$Resilient");
        assertThat(ratings.getClass().getName()).contains("_ClientProxy");
        long bytes = steadyState(() -> {
            long sum = 0;
            for (int i = 0; i < 100_000; i++) {
                sum += quotes.quote(i & 7) + ratings.rating(i & 7);
            }
            assertThat(sum).isPositive();
        });
        assertThat(bytes).as("bytes allocated by 100k calls of each bean").isLessThan(LIMIT);
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
