package dev.resilience4cata;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * {@link ResilienceSnapshot#toMap()}: written out per record, without reflection, so that it works in a native image
 * with no configuration. {@code ResilienceSnapshotTest} checks that every record component is here.
 */
final class SnapshotMaps {

    private SnapshotMaps() {
    }

    static Map<String, @Nullable Object> of(ResilienceSnapshot snapshot) {
        Map<String, @Nullable Object> map = new LinkedHashMap<>();
        map.put("circuitBreakers", list(snapshot.circuitBreakers(), SnapshotMaps::circuitBreaker));
        map.put("retries", list(snapshot.retries(), SnapshotMaps::retry));
        map.put("rateLimiters", list(snapshot.rateLimiters(), SnapshotMaps::rateLimiter));
        map.put("timeouts", list(snapshot.timeouts(), SnapshotMaps::timeout));
        map.put("bulkheads", list(snapshot.bulkheads(), SnapshotMaps::bulkhead));
        map.put("hedges", list(snapshot.hedges(), SnapshotMaps::hedge));
        map.put("adaptiveLimits", list(snapshot.adaptiveLimits(), SnapshotMaps::adaptiveLimit));
        return map;
    }

    private static <S> List<Map<String, @Nullable Object>> list(List<S> snapshots,
            Function<S, Map<String, @Nullable Object>> map) {
        List<Map<String, @Nullable Object>> list = new ArrayList<>(snapshots.size());
        for (S snapshot : snapshots) {
            list.add(map.apply(snapshot));
        }
        return list;
    }

    private static Map<String, @Nullable Object> circuitBreaker(CircuitBreakerSnapshot s) {
        Map<String, @Nullable Object> map = new LinkedHashMap<>();
        map.put("id", s.id());
        map.put("state", s.state().name());
        map.put("failureRate", value(s.failureRate()));
        map.put("slowCallRate", value(s.slowCallRate()));
        map.put("bufferedCalls", s.bufferedCalls());
        map.put("failedCalls", s.failedCalls());
        map.put("slowCalls", s.slowCalls());
        return map;
    }

    private static Map<String, @Nullable Object> retry(RetrySnapshot s) {
        Map<String, @Nullable Object> map = new LinkedHashMap<>();
        map.put("id", s.id());
        map.put("config", config(s.config()));
        RetrySnapshot.Budget budget = s.budget();
        if (budget == null) {
            map.put("budget", null);
        } else {
            Map<String, @Nullable Object> b = new LinkedHashMap<>();
            b.put("calls", budget.calls());
            b.put("retries", budget.retries());
            b.put("allowedRetries", budget.allowedRetries());
            map.put("budget", b);
        }
        return map;
    }

    private static Map<String, @Nullable Object> rateLimiter(RateLimiterSnapshot s) {
        Map<String, @Nullable Object> map = new LinkedHashMap<>();
        map.put("id", s.id());
        map.put("availablePermits", s.availablePermits());
        map.put("permits", s.permits());
        return map;
    }

    private static Map<String, @Nullable Object> timeout(TimeoutSnapshot s) {
        Map<String, @Nullable Object> map = new LinkedHashMap<>();
        map.put("id", s.id());
        map.put("config", config(s.config()));
        return map;
    }

    private static Map<String, @Nullable Object> bulkhead(BulkheadSnapshot s) {
        Map<String, @Nullable Object> map = new LinkedHashMap<>();
        map.put("id", s.id());
        map.put("concurrentCalls", s.concurrentCalls());
        map.put("waitingCalls", s.waitingCalls());
        map.put("maxConcurrent", s.maxConcurrent());
        return map;
    }

    private static Map<String, @Nullable Object> hedge(HedgeSnapshot s) {
        Map<String, @Nullable Object> map = new LinkedHashMap<>();
        map.put("id", s.id());
        map.put("config", config(s.config()));
        HedgeSnapshot.Budget budget = s.budget();
        if (budget == null) {
            map.put("budget", null);
        } else {
            Map<String, @Nullable Object> b = new LinkedHashMap<>();
            b.put("calls", budget.calls());
            b.put("hedges", budget.hedges());
            b.put("allowedHedges", budget.allowedHedges());
            map.put("budget", b);
        }
        return map;
    }

    private static Map<String, @Nullable Object> adaptiveLimit(AdaptiveLimitSnapshot s) {
        Map<String, @Nullable Object> map = new LinkedHashMap<>();
        map.put("id", s.id());
        map.put("algorithm", s.algorithm().name());
        map.put("limit", s.limit());
        map.put("concurrentCalls", s.concurrentCalls());
        map.put("minLimit", s.minLimit());
        map.put("maxLimit", s.maxLimit());
        map.put("windows", s.windows());
        map.put("windowLatency", s.windowLatency().toString());
        map.put("longTermLatency", s.longTermLatency().toString());
        return map;
    }

    private static Map<String, @Nullable Object> config(PolicyConfig config) {
        Map<String, Object> attributes = switch (config) {
            case RetryConfig c -> c.attributes();
            case HedgeConfig c -> c.attributes();
            case CircuitBreakerConfig c -> c.attributes();
            case RateLimiterConfig c -> c.attributes();
            case AdaptiveLimitConfig c -> c.attributes();
            case TimeoutConfig c -> c.attributes();
            case BulkheadConfig c -> c.attributes();
        };
        Map<String, @Nullable Object> map = new LinkedHashMap<>();
        attributes.forEach((key, v) -> map.put(key, value(v)));
        return map;
    }

    /** A JSON-friendly value: durations as ISO-8601, enums and classes by name, non-finite numbers as null. */
    static @Nullable Object value(@Nullable Object value) {
        return switch (value) {
            case null -> null;
            case Duration duration -> duration.toString();
            case Enum<?> constant -> constant.name();
            case Class<?> type -> type.getName();
            case Double d -> Double.isFinite(d) ? d : null;
            case Float f -> Float.isFinite(f) ? f : null;
            case Collection<?> collection -> {
                List<@Nullable Object> list = new ArrayList<>(collection.size());
                for (Object element : collection) {
                    list.add(value(element));
                }
                yield list;
            }
            default -> value;
        };
    }
}
