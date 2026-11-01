package dev.resilience4cata.quarkus.runtime.devui;

import dev.resilience4cata.AdaptiveLimitSnapshot;
import dev.resilience4cata.BulkheadSnapshot;
import dev.resilience4cata.CircuitBreakerHandle;
import dev.resilience4cata.CircuitBreakerSnapshot;
import dev.resilience4cata.HedgeSnapshot;
import dev.resilience4cata.RateLimiterSnapshot;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.ResilienceSnapshot;
import dev.resilience4cata.RetrySnapshot;
import dev.resilience4cata.TimeoutSnapshot;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The JsonRPC service behind the resilience4cata card of the Dev UI. Quarkus makes it a bean only in dev mode: the
 * deployment module registers it from a build step that runs only there, so in a production build the class is on
 * the class path but is never a bean and is never called.
 *
 * <p>Results are plain maps of strings, numbers and booleans, so that the Dev UI's JSON writer needs no module for
 * {@code Duration} or records. A value that has no meaning yet (a rate before the window is full, a latency before
 * the first window) is {@code null}; the page shows it as a dash.
 */
public class ResilienceJsonRpcService {

    @Inject
    ResilienceRegistry registry;

    /** Created by ArC. */
    public ResilienceJsonRpcService() {
    }

    /**
     * Every policy instance the registry has created so far, by kind.
     *
     * @return the snapshot as maps: {@code circuitBreakers}, {@code bulkheads}, {@code rateLimiters},
     *     {@code retries}, {@code timeouts}, {@code hedges}, {@code adaptiveLimits}
     */
    public Map<String, Object> getSnapshot() {
        ResilienceSnapshot snapshot = registry.snapshot();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("circuitBreakers", map(snapshot.circuitBreakers(), ResilienceJsonRpcService::breaker));
        result.put("bulkheads", map(snapshot.bulkheads(), ResilienceJsonRpcService::bulkhead));
        result.put("rateLimiters", map(snapshot.rateLimiters(), ResilienceJsonRpcService::rateLimiter));
        result.put("retries", map(snapshot.retries(), ResilienceJsonRpcService::retry));
        result.put("timeouts", map(snapshot.timeouts(), ResilienceJsonRpcService::timeout));
        result.put("hedges", map(snapshot.hedges(), ResilienceJsonRpcService::hedge));
        result.put("adaptiveLimits", map(snapshot.adaptiveLimits(), ResilienceJsonRpcService::adaptiveLimit));
        return result;
    }

    /**
     * The number of policy instances, for the card's label.
     *
     * @return for example {@code "5 instances"}
     */
    public String getInstanceCount() {
        ResilienceSnapshot s = registry.snapshot();
        int count = s.circuitBreakers().size() + s.bulkheads().size() + s.rateLimiters().size() + s.retries().size()
                + s.timeouts().size() + s.hedges().size() + s.adaptiveLimits().size();
        return count == 1 ? "1 instance" : count + " instances";
    }

    /**
     * Forces a circuit breaker open, as {@link CircuitBreakerHandle#forceOpen()} does.
     *
     * @param id the breaker's instance id
     * @return the breaker's snapshot afterwards
     */
    public Map<String, Object> forceOpen(String id) {
        CircuitBreakerHandle breaker = registry.circuitBreaker(id);
        breaker.forceOpen();
        return breaker(breaker.snapshot());
    }

    /**
     * Disables a circuit breaker (every call goes through), as {@link CircuitBreakerHandle#disable()} does.
     *
     * @param id the breaker's instance id
     * @return the breaker's snapshot afterwards
     */
    public Map<String, Object> disable(String id) {
        CircuitBreakerHandle breaker = registry.circuitBreaker(id);
        breaker.disable();
        return breaker(breaker.snapshot());
    }

    /**
     * Resets a circuit breaker to closed with an empty window, as {@link CircuitBreakerHandle#reset()} does.
     *
     * @param id the breaker's instance id
     * @return the breaker's snapshot afterwards
     */
    public Map<String, Object> reset(String id) {
        CircuitBreakerHandle breaker = registry.circuitBreaker(id);
        breaker.reset();
        return breaker(breaker.snapshot());
    }

    private static <T> List<Map<String, Object>> map(List<T> items, java.util.function.Function<T, Map<String, Object>> f) {
        List<Map<String, Object>> result = new ArrayList<>(items.size());
        for (T item : items) {
            result.add(f.apply(item));
        }
        return result;
    }

    private static Map<String, Object> breaker(CircuitBreakerSnapshot s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.id());
        m.put("state", s.state().name());
        m.put("failureRate", rate(s.failureRate()));
        m.put("slowCallRate", rate(s.slowCallRate()));
        m.put("bufferedCalls", s.bufferedCalls());
        m.put("failedCalls", s.failedCalls());
        m.put("slowCalls", s.slowCalls());
        return m;
    }

    private static Map<String, Object> bulkhead(BulkheadSnapshot s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.id());
        m.put("concurrentCalls", s.concurrentCalls());
        m.put("maxConcurrent", s.maxConcurrent());
        m.put("waitingCalls", s.waitingCalls());
        return m;
    }

    private static Map<String, Object> rateLimiter(RateLimiterSnapshot s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.id());
        m.put("availablePermits", s.availablePermits());
        m.put("permits", s.permits());
        return m;
    }

    private static Map<String, Object> retry(RetrySnapshot s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.id());
        m.put("maxAttempts", s.config().maxAttempts());
        RetrySnapshot.Budget budget = s.budget();
        m.put("budget", budget == null ? null : Map.of(
                "calls", budget.calls(), "retries", budget.retries(), "allowedRetries", budget.allowedRetries()));
        return m;
    }

    private static Map<String, Object> timeout(TimeoutSnapshot s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.id());
        m.put("timeout", duration(s.config().timeout()));
        return m;
    }

    private static Map<String, Object> hedge(HedgeSnapshot s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.id());
        m.put("delay", duration(s.config().delay()));
        m.put("maxAttempts", s.config().maxAttempts());
        HedgeSnapshot.Budget budget = s.budget();
        m.put("budget", budget == null ? null : Map.of(
                "calls", budget.calls(), "hedges", budget.hedges(), "allowedHedges", budget.allowedHedges()));
        return m;
    }

    private static Map<String, Object> adaptiveLimit(AdaptiveLimitSnapshot s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.id());
        m.put("algorithm", s.algorithm().name());
        m.put("limit", s.limit());
        m.put("concurrentCalls", s.concurrentCalls());
        m.put("minLimit", s.minLimit());
        m.put("maxLimit", s.maxLimit());
        m.put("windows", s.windows());
        m.put("windowLatency", s.windows() == 0 ? null : duration(s.windowLatency()));
        m.put("longTermLatency", s.windows() == 0 ? null : duration(s.longTermLatency()));
        return m;
    }

    /** A rate in percent, or {@code null} while the snapshot reports {@code -1} (not enough calls yet). */
    private static @Nullable Double rate(double rate) {
        return rate < 0 ? null : rate;
    }

    /** A duration as text: {@code 250 ms}, {@code 2 s}, {@code 75 µs}. */
    static String duration(Duration d) {
        long nanos = d.toNanos();
        if (nanos % 1_000_000_000L == 0) {
            return nanos / 1_000_000_000L + " s";
        }
        if (nanos >= 1_000_000L) {
            return trim(nanos / 1e6) + " ms";
        }
        if (nanos >= 1_000L) {
            return trim(nanos / 1e3) + " µs";
        }
        return nanos + " ns";
    }

    private static String trim(double value) {
        String text = String.format(java.util.Locale.ROOT, "%.1f", value);
        return text.endsWith(".0") ? text.substring(0, text.length() - 2) : text;
    }
}
