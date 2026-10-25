package dev.resilience4cata.generated;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.RetryConfig;
import dev.resilience4cata.RetrySnapshot;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The window arithmetic of {@link RetryBudget}, on a fake clock. */
class RetryBudgetTest {

    private static final long SECOND = 1_000_000_000L;
    private static final long START = 5 * SECOND;

    private static RetryBudget budget(double percent, int minPerSecond, Duration window) {
        RetryConfig config = RetryConfig.builder().budgetPercent(percent).budgetMinRetriesPerSecond(minPerSecond)
                .budgetWindow(window).build();
        return new RetryBudget(config, START, 4);
    }

    private static int acquired(RetryBudget budget, long now, int tries) {
        int granted = 0;
        for (int i = 0; i < tries; i++) {
            if (budget.tryAcquire(now)) {
                granted++;
            }
        }
        return granted;
    }

    private static void calls(RetryBudget budget, int calls) {
        for (int i = 0; i < calls; i++) {
            budget.onCall();
        }
    }

    @Test
    void theReserveAllowsMinRetriesPerSecondTimesTheWindowWithoutTraffic() {
        RetryBudget budget = budget(0, 2, Duration.ofSeconds(10));

        assertThat(acquired(budget, START + SECOND, 50)).isEqualTo(20);
        assertThat(budget.snapshot(START + SECOND)).isEqualTo(new RetrySnapshot.Budget(0, 20, 20));
    }

    @Test
    void thePercentAllowsAShareOfTheCallsOfTheWindow() {
        RetryBudget budget = budget(10, 0, Duration.ofSeconds(10));
        calls(budget, 100);

        assertThat(acquired(budget, START + SECOND, 50)).isEqualTo(10);
        calls(budget, 10);
        assertThat(acquired(budget, START + SECOND, 50)).as("one more retry for ten more calls").isEqualTo(1);
        assertThat(budget.snapshot(START + SECOND)).isEqualTo(new RetrySnapshot.Budget(110, 11, 11));
    }

    @Test
    void reserveAndPercentAddUp() {
        RetryBudget budget = budget(20, 1, Duration.ofSeconds(10));
        calls(budget, 50);

        assertThat(acquired(budget, START, 100)).isEqualTo(10 + 10);
    }

    @Test
    void callsAndRetriesExpireOnceTheWindowHasMovedPastThem() {
        RetryBudget budget = budget(50, 0, Duration.ofSeconds(10));
        assertThat(budget.tryAcquire(START)).as("first failure marks the window").isFalse();
        calls(budget, 10);
        assertThat(acquired(budget, START + SECOND, 10)).isEqualTo(5);

        // 9.5 s later the window (tenths of 1 s) still starts at the mark taken at START: nothing expired
        assertThat(acquired(budget, START + 9 * SECOND + SECOND / 2, 10)).isZero();
        // one tenth further, the oldest tenth is the one marked at START + 1 s: the 10 calls made before that
        // mark are gone, the 5 retries granted after it are not
        calls(budget, 4);
        assertThat(budget.snapshot(START + 10 * SECOND + SECOND / 2)).isEqualTo(new RetrySnapshot.Budget(4, 5, 2));
        assertThat(acquired(budget, START + 10 * SECOND + SECOND / 2, 10)).isZero();
        // one more tenth and the retries are gone too
        assertThat(acquired(budget, START + 11 * SECOND + SECOND / 2, 10)).isEqualTo(2);
        assertThat(budget.snapshot(START + 11 * SECOND + SECOND / 2)).isEqualTo(new RetrySnapshot.Budget(4, 2, 2));
    }

    @Test
    void callsMadeWhileNothingFailedForLongerThanTheWindowAreDroppedOnTheSafeSide() {
        RetryBudget budget = budget(50, 0, Duration.ofSeconds(10));
        calls(budget, 1_000);

        // no failure for a minute: the calls cannot be dated, and the oldest possible date is outside the window
        assertThat(budget.snapshot(START + 60 * SECOND)).isEqualTo(new RetrySnapshot.Budget(0, 0, 0));
        assertThat(budget.tryAcquire(START + 60 * SECOND)).isFalse();
        calls(budget, 4);
        assertThat(acquired(budget, START + 60 * SECOND, 10)).isEqualTo(2);
    }

    @Test
    void callsMadeWhileNothingFailedForLessThanTheWindowStillCount() {
        RetryBudget budget = budget(50, 0, Duration.ofSeconds(10));
        assertThat(budget.tryAcquire(START + 2 * SECOND)).isFalse();
        calls(budget, 10);

        // made after the mark at 2 s, which is still inside the window at 8 s
        assertThat(acquired(budget, START + 8 * SECOND, 10)).isEqualTo(5);
    }

    @Test
    void aThreadWithAnOlderClockReadingUsesTheNewerMarks() {
        RetryBudget budget = budget(0, 1, Duration.ofSeconds(10));
        assertThat(acquired(budget, START, 10)).isEqualTo(10);

        assertThat(budget.tryAcquire(START + 20 * SECOND)).as("the window moved: the reserve is back").isTrue();
        assertThat(acquired(budget, START + 5 * SECOND, 20)).as("older reading, same window").isEqualTo(9);
    }

    @Test
    void anExhaustedBudgetWritesNothing() {
        RetryBudget budget = budget(0, 1, Duration.ofSeconds(10));
        assertThat(acquired(budget, START, 10)).isEqualTo(10);
        RetrySnapshot.Budget before = budget.snapshot(START);

        assertThat(budget.tryAcquire(START)).isFalse();
        assertThat(budget.snapshot(START)).isEqualTo(before);
    }

    @Test
    void callsFromManyThreadsAreNeverOvercounted() throws InterruptedException {
        RetryBudget budget = budget(100, 0, Duration.ofSeconds(10));
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            threads.add(Thread.ofPlatform().start(() -> calls(budget, 10_000)));
        }
        for (Thread thread : threads) {
            thread.join();
        }

        // The increment is lossy on purpose: threads that share a stripe and race can lose calls (this tight
        // loop is the worst case), which only lowers the budget. It never counts a call that did not happen.
        assertThat(budget.snapshot(START).calls()).isBetween(40_000L, 80_000L);
    }

    @Test
    void callsFromOneThreadAreAllCounted() {
        RetryBudget budget = budget(100, 0, Duration.ofSeconds(10));
        calls(budget, 10_000);
        assertThat(budget.snapshot(START).calls()).isEqualTo(10_000);
    }

    @Test
    void stripesAreAPowerOfTwoOfAtLeastTwicePerProcessorCappedAt64() {
        assertThat(RetryBudget.stripes(1)).isEqualTo(2);
        assertThat(RetryBudget.stripes(3)).isEqualTo(8);
        assertThat(RetryBudget.stripes(4)).isEqualTo(8);
        assertThat(RetryBudget.stripes(5)).isEqualTo(16);
        assertThat(RetryBudget.stripes(1_000)).isEqualTo(64);
        assertThat(RetryBudget.stripes(0)).isEqualTo(2);
    }

    @Test
    void threadIdsThatFollowAPatternStillSpreadOverTheStripes() {
        // Pool threads often get sequential ids, or ids that are multiples of the stripe count; with the low
        // bits taken directly, every JMH worker landed on one stripe (and the lossy increment lost most calls).
        int stripes = 8;
        for (long stride : new long[] {1, 8, 16, 64, 1024}) {
            java.util.Set<Integer> used = new java.util.HashSet<>();
            for (long n = 0; n < stripes; n++) {
                used.add(RetryBudget.stripe(1000 + n * stride, stripes - 1));
            }
            assertThat(used).as("stride " + stride).hasSizeGreaterThanOrEqualTo(stripes / 2);
        }
        assertThat(RetryBudget.stripe(123, 0)).as("a single stripe").isZero();
    }
}
