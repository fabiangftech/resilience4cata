package dev.resilience4cata.examples;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.resilience4cata.BulkheadFullException;
import dev.resilience4cata.CallTimeoutException;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class BulkheadEndToEndTest {

    private final ResilienceRegistry registry = ResilienceRegistry.builder().build();
    private final CountDownLatch release = new CountDownLatch(1);
    private final CountDownLatch inside;

    private final ReportClient client;
    private final List<Thread> holders = new ArrayList<>();

    BulkheadEndToEndTest() {
        inside = new CountDownLatch(2);
        client = Resilient.of(ReportClient.class, new ReportClient() {
            @Override
            public String generate(String id) {
                return block(id);
            }

            @Override
            public String exportCsv(String id) {
                return block(id);
            }

            @Override
            public String exportPdf(String id) {
                return block(id);
            }

            @Override
            public String holdSlot(String id) {
                return block(id);
            }

            @Override
            public String waitForSlot(String id) {
                return block(id);
            }

            private String block(String id) {
                if (id.startsWith("hold")) {
                    inside.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return "report " + id;
            }
        }, registry);
    }

    @AfterEach
    void releaseHolders() throws InterruptedException {
        release.countDown();
        for (Thread holder : holders) {
            holder.join();
        }
        Thread.interrupted();
    }

    private void hold(Runnable call) {
        holders.add(Thread.ofVirtual().start(call));
    }

    @Test
    void rejectsCallsBeyondTheLimitAndAcceptsThemAgainLater() throws InterruptedException {
        hold(() -> client.generate("hold-1"));
        hold(() -> client.generate("hold-2"));
        assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> client.generate("third"))
                .isInstanceOf(BulkheadFullException.class)
                .hasMessageContaining("ReportClient#generate(java.lang.String)");
        assertThat(registry.bulkhead("dev.resilience4cata.examples.ReportClient#generate(java.lang.String)").concurrentCalls())
                .isEqualTo(2);

        release.countDown();
        for (Thread holder : holders) {
            holder.join();
        }
        assertThat(client.generate("later")).isEqualTo("report later");
    }

    @Test
    void methodsWithTheSameNameShareOneBulkhead() throws InterruptedException {
        hold(() -> client.exportCsv("hold-csv"));
        while (registry.bulkhead("exports").concurrentCalls() == 0) {
            Thread.onSpinWait();
        }
        assertThatThrownBy(() -> client.exportPdf("pdf")).isInstanceOf(BulkheadFullException.class)
                .hasMessageContaining("'exports'");
    }

    @Test
    void aTimeoutStopsTheWaitForASlot() {
        // The holder has no timeout: one that timed out first would free the slot before the second call waits.
        hold(() -> client.holdSlot("hold-slot"));
        while (registry.bulkhead("slots").concurrentCalls() == 0) {
            Thread.onSpinWait();
        }
        long start = System.nanoTime();
        assertThatThrownBy(() -> client.waitForSlot("waiting")).isInstanceOf(CallTimeoutException.class);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(2_000);
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }
}
