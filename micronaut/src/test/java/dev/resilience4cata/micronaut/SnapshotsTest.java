package dev.resilience4cata.micronaut;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.BulkheadSnapshot;
import dev.resilience4cata.CircuitBreakerSnapshot;
import dev.resilience4cata.CircuitBreakerState;
import dev.resilience4cata.ResilienceSnapshot;
import dev.resilience4cata.RetryConfig;
import dev.resilience4cata.RetrySnapshot;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The endpoint's JSON-ready view of a snapshot. */
class SnapshotsTest {

    private static final ResilienceSnapshot SNAPSHOT = new ResilienceSnapshot(
            List.of(new CircuitBreakerSnapshot("orders", CircuitBreakerState.OPEN, 60f, Float.NaN, 10, 6, 0)),
            List.of(new RetrySnapshot("orders", RetryConfig.DEFAULTS, new RetrySnapshot.Budget(5, 1, 2))),
            List.of(), List.of(), List.of(new BulkheadSnapshot("db", 1, 0, 4)), List.of(),
            List.of(new dev.resilience4cata.AdaptiveLimitSnapshot("search", dev.resilience4cata.AdaptiveLimitConfig.Algorithm.AIMD,
                    12, 3, 1, 200, 7, java.time.Duration.ofMillis(40), java.time.Duration.ZERO)));

    @Test
    void recordsBecomeMapsInDeclarationOrderWithPlainValues() {
        Map<String, Object> map = Snapshots.of(SNAPSHOT);

        assertThat(map).containsOnlyKeys("circuitBreakers", "retries", "rateLimiters", "timeouts", "bulkheads", "hedges",
                "adaptiveLimits");
        Map<?, ?> limit = (Map<?, ?>) ((List<?>) map.get("adaptiveLimits")).get(0);
        assertThat(limit.get("algorithm")).isEqualTo("AIMD");
        assertThat(limit.get("limit")).isEqualTo(12);
        assertThat(limit.get("windowLatency")).isEqualTo("PT0.04S");
        Map<?, ?> breaker = (Map<?, ?>) ((List<?>) map.get("circuitBreakers")).get(0);
        assertThat(breaker.keySet().stream().map(String::valueOf).toList()).containsExactly("instance", "state", "failureRate", "slowCallRate", "bufferedCalls",
                "failedCalls", "slowCalls");
        assertThat(breaker.get("state")).isEqualTo("OPEN");
        assertThat(breaker.get("slowCallRate")).as("NaN is not JSON").isNull();
        Map<?, ?> retry = (Map<?, ?>) ((List<?>) map.get("retries")).get(0);
        Map<?, ?> config = (Map<?, ?>) retry.get("config");
        assertThat(config.get("delay")).isEqualTo("PT0.2S");
        assertThat(config.get("retryOn")).isEqualTo(List.of("java.lang.Exception"));
        assertThat(config.get("jitter")).isEqualTo("FULL");
        assertThat(retry.get("budget")).isEqualTo(Map.of("calls", 5L, "retries", 1L, "allowedRetries", 2L));
    }

    @Test
    void oneInstanceOrNothing() {
        assertThat(Snapshots.only(SNAPSHOT, "db")).satisfies(one -> {
            assertThat((List<?>) one.get("bulkheads")).hasSize(1);
            assertThat((List<?>) one.get("circuitBreakers")).isEmpty();
        });
        assertThat(Snapshots.only(SNAPSHOT, "nobody")).isNull();
    }
}
