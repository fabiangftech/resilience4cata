package dev.resilience4cata.examples.hedgesim;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.examples.hedgesim.Downstream.CancelMode;
import dev.resilience4cata.testkit.ManualClock;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/** The simulated dependency of {@link HedgeTailSimulation} behaves as its Javadoc says. */
class DownstreamTest {

    private final ManualClock clock = new ManualClock();

    /** Every request takes exactly 10 ms. */
    private Downstream downstream(int workers, CancelMode mode) {
        return new Downstream(clock, new ServiceTime(1, 10_000_000L, 0, 0, 0, 0), workers, mode);
    }

    @Test
    void servesAtMostWorkersAtATimeInArrivalOrder() {
        Downstream downstream = downstream(2, CancelMode.IGNORED);
        CompletableFuture<Long> a = downstream.call();
        CompletableFuture<Long> b = downstream.call();
        CompletableFuture<Long> c = downstream.call();
        assertThat(downstream.busy()).isEqualTo(2);
        assertThat(downstream.queued()).isOne();

        clock.advance(Duration.ofMillis(10));
        assertThat(a).isDone();
        assertThat(b).isDone();
        assertThat(c).isNotDone();
        clock.advance(Duration.ofMillis(10));
        assertThat(c).isDone();
        assertThat(downstream.busyNanos()).isEqualTo(30_000_000L);
    }

    @Test
    void anIgnoredCancellationStillCostsTheWork() {
        Downstream downstream = downstream(1, CancelMode.IGNORED);
        CompletableFuture<Long> a = downstream.call();
        CompletableFuture<Long> b = downstream.call();
        a.cancel(false);
        b.cancel(false);
        clock.advanceUntilIdle();
        assertThat(clock.read()).isEqualTo(20_000_000L);
        assertThat(downstream.wastedNanos()).isEqualTo(20_000_000L);
    }

    @Test
    void aCancellationThatReachesTheServerFreesTheWorkerAndTheQueue() {
        Downstream downstream = downstream(1, CancelMode.REACHES_SERVER);
        CompletableFuture<Long> a = downstream.call();
        CompletableFuture<Long> b = downstream.call();
        CompletableFuture<Long> c = downstream.call();
        clock.advance(Duration.ofMillis(4));
        a.cancel(false);
        b.cancel(false);
        assertThat(downstream.busy()).as("c took the worker at once").isOne();
        clock.advanceUntilIdle();
        assertThat(c).isCompletedWithValue(10_000_000L);
        assertThat(clock.read()).isEqualTo(14_000_000L);
        assertThat(downstream.wastedNanos()).isEqualTo(4_000_000L);
    }
}
