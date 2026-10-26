package dev.resilience4cata.generated;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.AdaptiveLimitConfig;
import dev.resilience4cata.AdaptiveLimitConfig.Algorithm;
import org.junit.jupiter.api.Test;

/** The two algorithms, one window at a time, against values computed from Netflix's formulas. */
class LimitAlgorithmTest {

    private static final long MS = 1_000_000L;

    private static LimitAlgorithm gradient2(int initial, int min, int max) {
        return LimitAlgorithm.of(AdaptiveLimitConfig.builder().algorithm(Algorithm.GRADIENT2).initialLimit(initial)
                .minLimit(min).maxLimit(max).build());
    }

    private static LimitAlgorithm aimd(int initial, int min, int max) {
        return LimitAlgorithm.of(AdaptiveLimitConfig.builder().algorithm(Algorithm.AIMD).initialLimit(initial)
                .minLimit(min).maxLimit(max).build());
    }

    @Test
    void gradient2GrowsByAFifthOfTheQueueSizePerWindowWhileLatencyHolds() {
        LimitAlgorithm algorithm = gradient2(20, 1, 200);
        // gradient 1 (latency within tolerance): 20 * 0.8 + (20 + 4) * 0.2 = 20.8, then 21.6, 22.4, ...
        assertThat(algorithm.update(20, 10 * MS, 20, false)).isEqualTo(20);
        assertThat(algorithm.update(20, 10 * MS, 20, false)).isEqualTo(21);
        assertThat(algorithm.update(21, 10 * MS, 21, false)).isEqualTo(22);
        int limit = 22;
        for (int i = 0; i < 1_000; i++) {
            limit = algorithm.update(limit, 10 * MS, limit, false);
        }
        assertThat(limit).as("grows up to maxLimit").isEqualTo(200);
    }

    @Test
    void gradient2DoesNotGrowWhenLessThanHalfTheLimitIsInUse() {
        LimitAlgorithm algorithm = gradient2(20, 1, 200);
        for (int i = 0; i < 50; i++) {
            assertThat(algorithm.update(20, 10 * MS, 9, false)).isEqualTo(20);
        }
        assertThat(algorithm.longTermNanos()).as("the latency is still learned").isCloseTo(10.0 * MS, org.assertj.core.data.Offset.offset(1.0));
    }

    @Test
    void gradient2ShrinksWhenLatencyRisesAboveTheTolerance() {
        LimitAlgorithm algorithm = gradient2(100, 1, 200);
        int limit = 100;
        for (int i = 0; i < 10; i++) { // warm-up: the long-term latency is the plain average, 10 ms
            limit = algorithm.update(limit, 10 * MS, limit, false);
        }
        assertThat(algorithm.longTermNanos()).isEqualTo(10.0 * MS);
        int before = limit;
        // 50 ms is 5 times the long-term latency, beyond the tolerance of 1.5: the gradient is clamped to 0.5,
        // so the estimate moves to e * 0.8 + (e * 0.5 + 4) * 0.2 = 0.9 e + 0.8.
        limit = algorithm.update(limit, 50 * MS, limit, false);
        assertThat(limit).isLessThan(before).isGreaterThanOrEqualTo((int) (before * 0.9));
        for (int i = 0; i < 100; i++) {
            limit = algorithm.update(limit, 50 * MS, limit, false);
        }
        assertThat(limit).as("keeps shrinking while latency stays high").isLessThan(before / 2);
    }

    @Test
    void gradient2StaysWithinItsBounds() {
        LimitAlgorithm algorithm = gradient2(5, 5, 6);
        int limit = 5;
        for (int i = 0; i < 100; i++) {
            limit = algorithm.update(limit, 10 * MS, limit, false);
        }
        assertThat(limit).isEqualTo(6);
        LimitAlgorithm noQueue = LimitAlgorithm.of(AdaptiveLimitConfig.builder().initialLimit(6).minLimit(5).maxLimit(6)
                .queueSize(0).build());
        limit = 6;
        for (int i = 0; i < 10; i++) {
            limit = noQueue.update(limit, 10 * MS, limit, false);
        }
        for (int i = 0; i < 100; i++) {
            limit = noQueue.update(limit, 1_000 * MS, limit, false);
        }
        assertThat(limit).isEqualTo(5);
    }

    @Test
    void gradient2DoesNotGoMuchBelowTwiceTheQueueSize() {
        // With the gradient at its floor of 0.5, the estimate moves to 0.9 e + 0.2 queueSize, which stops at
        // 2 queueSize: latency alone does not take the limit below 8 with the default queue size of 4.
        LimitAlgorithm algorithm = gradient2(100, 1, 200);
        int limit = 100;
        for (int i = 0; i < 10; i++) {
            limit = algorithm.update(limit, MS, limit, false);
        }
        for (int i = 0; i < 60; i++) { // short of the long-term average catching up, which would lift the gradient
            limit = algorithm.update(limit, 1_000_000 * MS, limit, false);
        }
        assertThat(limit).isBetween(8, 9);
    }

    @Test
    void gradient2BringsTheLongTermLatencyDownFasterWhenLatencyRecovers() {
        LimitAlgorithm algorithm = gradient2(20, 1, 200);
        for (int i = 0; i < 10; i++) {
            algorithm.update(20, 100 * MS, 20, false);
        }
        algorithm.update(20, 10 * MS, 20, false);
        // the exponential average alone would be 100 - 90 * 2 / 601 = 99.7 ms; it is then cut by 5 %
        assertThat(algorithm.longTermNanos()).isCloseTo((100 - 90 * 2.0 / 601) * MS * 0.95, org.assertj.core.data.Offset.offset(1.0));
    }

    @Test
    void aimdAddsOneAfterABusyWindowAndBacksOffAfterADrop() {
        LimitAlgorithm algorithm = aimd(20, 1, 200);
        assertThat(algorithm.update(20, 10 * MS, 10, false)).as("half the limit in use").isEqualTo(21);
        assertThat(algorithm.update(21, 10 * MS, 10, false)).as("less than half in use").isEqualTo(21);
        assertThat(algorithm.update(21, 10 * MS, 21, true)).as("a drop: 21 * 0.9").isEqualTo(18);
        assertThat(algorithm.update(18, 10_000 * MS, 18, false)).as("latency alone does not matter").isEqualTo(19);
    }

    @Test
    void aimdStaysWithinItsBounds() {
        LimitAlgorithm algorithm = aimd(1, 1, 3);
        assertThat(algorithm.update(1, MS, 1, true)).as("1 * 0.9 rounds down to 0, kept at minLimit").isEqualTo(1);
        assertThat(algorithm.update(3, MS, 3, false)).isEqualTo(3);
        assertThat(algorithm.longTermNanos()).isZero();
    }
}
