package dev.resilience4cata.generated;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.BulkheadConfig;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class BulkheadPolicyTest {

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    private static BulkheadPolicy bulkhead(int maxConcurrent, Duration maxWait) {
        return new BulkheadPolicy("test", BulkheadConfig.builder().maxConcurrent(maxConcurrent).maxWait(maxWait).build());
    }

    @Test
    void rejectsOnceFullAndAcceptsAgainAfterExit() {
        BulkheadPolicy bulkhead = bulkhead(2, Duration.ZERO);
        assertThat(bulkhead.tryEnter()).isTrue();
        assertThat(bulkhead.tryEnter()).isTrue();
        assertThat(bulkhead.tryEnter()).isFalse();
        assertThat(bulkhead.concurrentCalls()).isEqualTo(2);

        bulkhead.exit();
        assertThat(bulkhead.concurrentCalls()).isEqualTo(1);
        assertThat(bulkhead.tryEnter()).isTrue();
        assertThat(bulkhead.waitingCalls()).as("nothing waits without maxWait").isZero();
    }

    @Test
    void countsSynchronousCallersBlockedOnAFullBulkhead() throws Exception {
        BulkheadPolicy bulkhead = bulkhead(1, Duration.ofHours(1));
        assertThat(bulkhead.tryEnter()).isTrue();
        assertThat(bulkhead.waitingCalls()).isZero();

        Thread blocked = Thread.ofPlatform().start(() -> {
            if (bulkhead.tryEnter()) {
                bulkhead.exit();
            }
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (bulkhead.waitingCalls() == 0 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(bulkhead.waitingCalls()).isEqualTo(1);

        bulkhead.exit();
        blocked.join(TimeUnit.SECONDS.toMillis(5));
        assertThat(blocked.isAlive()).isFalse();
        assertThat(bulkhead.waitingCalls()).isZero();
        assertThat(bulkhead.concurrentCalls()).isZero();
    }

    @Test
    void neverLetsMoreThanTheMaximumRunConcurrently() throws InterruptedException {
        for (Duration maxWait : new Duration[] {Duration.ZERO, Duration.ofMillis(1)}) {
            BulkheadPolicy bulkhead = bulkhead(3, maxWait);
            AtomicInteger running = new AtomicInteger();
            AtomicInteger maxSeen = new AtomicInteger();
            AtomicInteger accepted = new AtomicInteger();
            CountDownLatch start = new CountDownLatch(1);
            List<Thread> workers = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                workers.add(Thread.ofPlatform().start(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        return;
                    }
                    for (int i = 0; i < 20_000; i++) {
                        if (bulkhead.tryEnter()) {
                            try {
                                accepted.incrementAndGet();
                                maxSeen.accumulateAndGet(running.incrementAndGet(), Math::max);
                                Thread.onSpinWait();
                                running.decrementAndGet();
                            } finally {
                                bulkhead.exit();
                            }
                        }
                    }
                }));
            }
            start.countDown();
            for (Thread worker : workers) {
                worker.join();
            }
            assertThat(maxSeen.get()).as("maxWait " + maxWait).isBetween(1, 3);
            assertThat(accepted.get()).isPositive();
            assertThat(bulkhead.concurrentCalls()).isZero();
        }
    }

    @Test
    void waitsForASlotThatIsFreedInTime() throws InterruptedException {
        BulkheadPolicy bulkhead = bulkhead(1, Duration.ofSeconds(5));
        assertThat(bulkhead.tryEnter()).isTrue();
        AtomicBoolean entered = new AtomicBoolean();
        Thread waiter = Thread.ofPlatform().start(() -> entered.set(bulkhead.tryEnter()));
        TimeUnit.MILLISECONDS.sleep(50);
        bulkhead.exit();
        waiter.join(5_000);
        assertThat(entered).isTrue();
    }

    @Test
    void givesUpAfterMaxWait() {
        BulkheadPolicy bulkhead = bulkhead(1, Duration.ofMillis(50));
        assertThat(bulkhead.tryEnter()).isTrue();
        long start = System.nanoTime();
        assertThat(bulkhead.tryEnter()).isFalse();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(50));
    }

    @Test
    void anInterruptedWaitIsARejectionAndKeepsTheInterrupt() {
        BulkheadPolicy bulkhead = bulkhead(1, Duration.ofSeconds(10));
        assertThat(bulkhead.tryEnter()).isTrue();
        Thread.currentThread().interrupt();
        assertThat(bulkhead.tryEnter()).isFalse();
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    void disabledLetsEverythingThrough() {
        BulkheadPolicy bulkhead = new BulkheadPolicy("test", BulkheadConfig.builder().maxConcurrent(1).enabled(false).build());
        for (int i = 0; i < 10; i++) {
            assertThat(bulkhead.tryEnter()).isTrue();
        }
        bulkhead.exit();
    }
}
