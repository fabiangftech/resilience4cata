package dev.resilience4cata.examples.docs.policies;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.resilience4cata.CallTimeoutException;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import dev.resilience4cata.testkit.ManualClock;
import dev.resilience4cata.testkit.ResilienceTesting;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** What the policies page says about {@code @PolicyOrder}, on a manual clock. */
class PolicyOrderTest {

    private final ManualClock clock = new ManualClock();
    private final ResilienceRegistry registry = ResilienceTesting.registry(clock).build();
    private final AtomicInteger calls = new AtomicInteger();

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @Test
    void aTimeoutAroundTheRetryBoundsEveryAttemptTogether() {
        ReportApi reports = Resilient.of(ReportApi.class, id -> {
            calls.incrementAndGet();
            throw new IllegalStateException("not ready");
        }, registry);

        assertThatThrownBy(() -> reports.report("r-1")).isInstanceOf(CallTimeoutException.class);
        // attempts at 0, 300 and 900 ms (exponential backoff); the next pause passes the one-second deadline,
        // with seven attempts left: per attempt, as by default, the timeout would never have fired
        assertThat(calls).hasValue(3);
        assertThat(clock.elapsed()).isGreaterThanOrEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void aBreakerOutsideTheRetryRecordsTheWholeOperationOnce() {
        SettlementApi settlements = Resilient.of(SettlementApi.class, id -> {
            calls.incrementAndGet();
            throw new IllegalStateException("down");
        }, registry);

        assertThatThrownBy(() -> settlements.settle("s-1")).isInstanceOf(IllegalStateException.class);
        assertThat(calls).as("three attempts").hasValue(3);
        assertThat(registry.circuitBreaker("settlement").snapshot().failedCalls()).as("one recorded failure").isEqualTo(1);
    }
}
