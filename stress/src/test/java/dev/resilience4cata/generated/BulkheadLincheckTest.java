package dev.resilience4cata.generated;

import dev.resilience4cata.BulkheadConfig;
import java.time.Duration;
import org.jetbrains.lincheck.datastructures.Operation;
import org.junit.jupiter.api.Test;

/**
 * The bulkhead's slot counter is linearizable, without {@code maxWait} (a padded counter) and with it (the
 * semaphore, through its no-wait path: a waiting call blocks, which Lincheck cannot interleave with). An
 * {@code exit} without an {@code enter} is allowed here: the one-at-a-time run does the same, so the answers still
 * have to agree.
 */
class BulkheadLincheckTest {

    @Test
    void theCounterIsLinearizable() {
        Lincheck.modelChecking().check(Counter.class);
    }

    @Test
    void theSemaphoreIsLinearizable() {
        Lincheck.modelChecking().check(Semaphore.class);
    }

    public static class Counter {
        private final BulkheadPolicy bulkhead =
                new BulkheadPolicy("lincheck", BulkheadConfig.builder().maxConcurrent(2).build());

        @Operation
        public boolean enter() {
            return bulkhead.tryEnter();
        }

        @Operation
        public void exit() {
            bulkhead.exit();
        }

        @Operation
        public int concurrentCalls() {
            return bulkhead.concurrentCalls();
        }
    }

    public static class Semaphore {
        private final BulkheadPolicy bulkhead = new BulkheadPolicy("lincheck",
                BulkheadConfig.builder().maxConcurrent(2).maxWait(Duration.ofSeconds(1)).build());

        @Operation
        public boolean enter() {
            return bulkhead.tryAcquireForTest();
        }

        @Operation
        public void exit() {
            bulkhead.exit();
        }

        @Operation
        public int concurrentCalls() {
            return bulkhead.concurrentCalls();
        }
    }
}
