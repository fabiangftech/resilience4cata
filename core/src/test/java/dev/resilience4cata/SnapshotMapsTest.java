package dev.resilience4cata;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@link ResilienceSnapshot#toMap()}: the JSON shape of the Spring Boot and Micronaut endpoints. */
class SnapshotMapsTest {

    private static final ResilienceSnapshot SNAPSHOT = new ResilienceSnapshot(
            List.of(new CircuitBreakerSnapshot("orders", CircuitBreakerState.OPEN, 60f, Float.NaN, 10, 6, 0)),
            List.of(new RetrySnapshot("orders", RetryConfig.DEFAULTS, new RetrySnapshot.Budget(5, 1, 2))),
            List.of(new RateLimiterSnapshot("search", 3, 10)),
            List.of(new TimeoutSnapshot("orders", TimeoutConfig.DEFAULTS)),
            List.of(new BulkheadSnapshot("db", 1, 0, 4)),
            List.of(new HedgeSnapshot("quotes", HedgeConfig.DEFAULTS, null)),
            List.of(new AdaptiveLimitSnapshot("search", AdaptiveLimitConfig.Algorithm.AIMD, 12, 3, 1, 200, 7,
                    Duration.ofMillis(40), Duration.ZERO)));

    @Test
    void recordsBecomeMapsInDeclarationOrderWithPlainValues() {
        Map<String, Object> map = SNAPSHOT.toMap();

        assertThat(map).containsOnlyKeys("circuitBreakers", "retries", "rateLimiters", "timeouts", "bulkheads",
                "hedges", "adaptiveLimits");
        Map<?, ?> limit = first(map, "adaptiveLimits");
        assertThat(limit.get("algorithm")).isEqualTo("AIMD");
        assertThat(limit.get("limit")).isEqualTo(12);
        assertThat(limit.get("windowLatency")).isEqualTo("PT0.04S");
        Map<?, ?> breaker = first(map, "circuitBreakers");
        assertThat(keys(breaker)).containsExactly("instance", "state",
                "failureRate", "slowCallRate", "bufferedCalls", "failedCalls", "slowCalls");
        assertThat(breaker.get("state")).isEqualTo("OPEN");
        assertThat(breaker.get("slowCallRate")).as("NaN is not JSON").isNull();
        Map<?, ?> retry = first(map, "retries");
        Map<?, ?> config = (Map<?, ?>) retry.get("config");
        assertThat(config.get("delay")).isEqualTo("PT0.2S");
        assertThat(config.get("retryOn")).isEqualTo(List.of("java.lang.Exception"));
        assertThat(config.get("jitter")).isEqualTo("FULL");
        assertThat(retry.get("budget")).isEqualTo(Map.of("calls", 5L, "retries", 1L, "allowedRetries", 2L));
        assertThat(first(map, "hedges").containsKey("budget")).isTrue();
        assertThat(first(map, "hedges").get("budget")).isNull();
    }

    /** A component added to a snapshot record, or an attribute added to a config, must reach the map too. */
    @Test
    void everyComponentAndAttributeIsInTheMap() {
        Map<String, Object> map = SNAPSHOT.toMap();
        assertThat(keys(first(map, "circuitBreakers"))).containsExactlyElementsOf(components(CircuitBreakerSnapshot.class));
        assertThat(keys(first(map, "retries"))).containsExactlyElementsOf(components(RetrySnapshot.class));
        assertThat(keys(first(map, "rateLimiters"))).containsExactlyElementsOf(components(RateLimiterSnapshot.class));
        assertThat(keys(first(map, "timeouts"))).containsExactlyElementsOf(components(TimeoutSnapshot.class));
        assertThat(keys(first(map, "bulkheads"))).containsExactlyElementsOf(components(BulkheadSnapshot.class));
        assertThat(keys(first(map, "hedges"))).containsExactlyElementsOf(components(HedgeSnapshot.class));
        assertThat(keys(first(map, "adaptiveLimits"))).containsExactlyElementsOf(components(AdaptiveLimitSnapshot.class));
        assertThat(keys((Map<?, ?>) first(map, "retries").get("budget")))
                .containsExactlyElementsOf(components(RetrySnapshot.Budget.class));
        for (Class<?> config : List.of(RetryConfig.class, HedgeConfig.class, CircuitBreakerConfig.class,
                RateLimiterConfig.class, AdaptiveLimitConfig.class, TimeoutConfig.class, BulkheadConfig.class)) {
            List<String> fields = Arrays.stream(config.getDeclaredFields())
                    .filter(f -> !Modifier.isStatic(f.getModifiers())).map(Field::getName).toList();
            PolicyConfig defaults = defaults(config);
            Map<String, Object> attributes = switch (defaults) {
                case RetryConfig c -> c.attributes();
                case HedgeConfig c -> c.attributes();
                case CircuitBreakerConfig c -> c.attributes();
                case RateLimiterConfig c -> c.attributes();
                case AdaptiveLimitConfig c -> c.attributes();
                case TimeoutConfig c -> c.attributes();
                case BulkheadConfig c -> c.attributes();
            };
            assertThat(attributes.keySet()).as(config.getSimpleName()).containsExactlyElementsOf(fields);
        }
    }

    @Test
    void oneInstanceOrNothing() {
        ResilienceSnapshot db = SNAPSHOT.only("db");
        assertThat(db.bulkheads()).hasSize(1);
        assertThat(db.circuitBreakers()).isEmpty();
        assertThat(db.isEmpty()).isFalse();
        assertThat(SNAPSHOT.only("nobody").isEmpty()).isTrue();
        assertThat((List<?>) SNAPSHOT.only("orders").toMap().get("retries")).hasSize(1);
    }

    private static Map<?, ?> first(Map<String, Object> map, String kind) {
        return (Map<?, ?>) ((List<?>) map.get(kind)).get(0);
    }

    private static List<String> keys(Map<?, ?> map) {
        return map.keySet().stream().map(String::valueOf).toList();
    }

    private static List<String> components(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList();
    }

    private static PolicyConfig defaults(Class<?> type) {
        try {
            return (PolicyConfig) type.getField("DEFAULTS").get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
