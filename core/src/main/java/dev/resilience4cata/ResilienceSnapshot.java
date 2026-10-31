package dev.resilience4cata;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Read-only view of every policy instance of a registry at one moment, for health checks, admin
 * endpoints and metrics. Returned by {@link ResilienceRegistry#snapshot()}; each list is sorted by
 * instance id. Instances exist once their interface has been wrapped with {@code Resilient.of(...)}.
 *
 * <p>Values are read without stopping the calls in flight, so they can lag behind by a few calls and
 * are not taken at exactly the same instant; each is clamped to its valid range.
 *
 * <p>Not a record, so that a policy kind added in a minor version is one more list here rather than a new
 * constructor: only the registry creates snapshots. {@link #toMap()} is the shape the Spring Boot and Micronaut
 * endpoints serve.
 */
public final class ResilienceSnapshot {

    private final List<CircuitBreakerSnapshot> circuitBreakers;
    private final List<RetrySnapshot> retries;
    private final List<RateLimiterSnapshot> rateLimiters;
    private final List<TimeoutSnapshot> timeouts;
    private final List<BulkheadSnapshot> bulkheads;
    private final List<HedgeSnapshot> hedges;
    private final List<AdaptiveLimitSnapshot> adaptiveLimits;

    /** Copies the lists. */
    ResilienceSnapshot(List<CircuitBreakerSnapshot> circuitBreakers, List<RetrySnapshot> retries,
            List<RateLimiterSnapshot> rateLimiters, List<TimeoutSnapshot> timeouts, List<BulkheadSnapshot> bulkheads,
            List<HedgeSnapshot> hedges, List<AdaptiveLimitSnapshot> adaptiveLimits) {
        this.circuitBreakers = List.copyOf(circuitBreakers);
        this.retries = List.copyOf(retries);
        this.rateLimiters = List.copyOf(rateLimiters);
        this.timeouts = List.copyOf(timeouts);
        this.bulkheads = List.copyOf(bulkheads);
        this.hedges = List.copyOf(hedges);
        this.adaptiveLimits = List.copyOf(adaptiveLimits);
    }

    /**
     * Every circuit breaker.
     *
     * @return the snapshots, sorted by id
     */
    public List<CircuitBreakerSnapshot> circuitBreakers() {
        return circuitBreakers;
    }

    /**
     * Every retry.
     *
     * @return the snapshots, sorted by id
     */
    public List<RetrySnapshot> retries() {
        return retries;
    }

    /**
     * Every rate limiter.
     *
     * @return the snapshots, sorted by id
     */
    public List<RateLimiterSnapshot> rateLimiters() {
        return rateLimiters;
    }

    /**
     * Every timeout.
     *
     * @return the snapshots, sorted by id
     */
    public List<TimeoutSnapshot> timeouts() {
        return timeouts;
    }

    /**
     * Every bulkhead.
     *
     * @return the snapshots, sorted by id
     */
    public List<BulkheadSnapshot> bulkheads() {
        return bulkheads;
    }

    /**
     * Every hedge.
     *
     * @return the snapshots, sorted by id
     */
    public List<HedgeSnapshot> hedges() {
        return hedges;
    }

    /**
     * Every adaptive limit.
     *
     * @return the snapshots, sorted by id
     */
    public List<AdaptiveLimitSnapshot> adaptiveLimits() {
        return adaptiveLimits;
    }

    /**
     * Whether there is no instance at all.
     *
     * @return {@code true} when every list is empty
     */
    public boolean isEmpty() {
        return circuitBreakers.isEmpty() && retries.isEmpty() && rateLimiters.isEmpty() && timeouts.isEmpty()
                && bulkheads.isEmpty() && hedges.isEmpty() && adaptiveLimits.isEmpty();
    }

    /**
     * The instances with one id: a policy's {@code name}, a derived {@code <type>#<method>(<parameter types>)} id or
     * a pipeline id.
     *
     * @param id the instance id
     * @return a snapshot of the policies with that id; {@linkplain #isEmpty() empty} when there is none
     */
    public ResilienceSnapshot only(String id) {
        Objects.requireNonNull(id, "id");
        return new ResilienceSnapshot(only(circuitBreakers, id, CircuitBreakerSnapshot::id),
                only(retries, id, RetrySnapshot::id), only(rateLimiters, id, RateLimiterSnapshot::id),
                only(timeouts, id, TimeoutSnapshot::id), only(bulkheads, id, BulkheadSnapshot::id),
                only(hedges, id, HedgeSnapshot::id), only(adaptiveLimits, id, AdaptiveLimitSnapshot::id));
    }

    private static <S> List<S> only(List<S> snapshots, String id, Function<S, String> idOf) {
        return snapshots.stream().filter(s -> idOf.apply(s).equals(id)).toList();
    }

    /**
     * The snapshot as maps, lists, strings, numbers, booleans and nulls, ready for any JSON encoder: the shape the
     * Spring Boot actuator and Micronaut management endpoints serve, documented as a contract in the observability
     * guide. One key per policy kind (the accessor names above), each a list of one map per instance; a map's keys
     * are the snapshot's components in declaration order, configurations nested as maps of their attributes.
     * Durations are ISO-8601 strings ({@code PT0.2S}), enums and classes their names, and a rate that is not a
     * finite number is {@code null}. Keys may be added in minor versions, never removed or renamed.
     *
     * @return a new, mutable map
     */
    public Map<String, @Nullable Object> toMap() {
        return SnapshotMaps.of(this);
    }

    /** Equal when every list is equal. */
    @Override
    public boolean equals(Object o) {
        return o instanceof ResilienceSnapshot other && circuitBreakers.equals(other.circuitBreakers)
                && retries.equals(other.retries) && rateLimiters.equals(other.rateLimiters)
                && timeouts.equals(other.timeouts) && bulkheads.equals(other.bulkheads) && hedges.equals(other.hedges)
                && adaptiveLimits.equals(other.adaptiveLimits);
    }

    @Override
    public int hashCode() {
        return Objects.hash(circuitBreakers, retries, rateLimiters, timeouts, bulkheads, hedges, adaptiveLimits);
    }

    /** Every list, as {@code ResilienceSnapshot[circuitBreakers=[...], ...]}. */
    @Override
    public String toString() {
        return "ResilienceSnapshot[circuitBreakers=" + circuitBreakers + ", retries=" + retries + ", rateLimiters="
                + rateLimiters + ", timeouts=" + timeouts + ", bulkheads=" + bulkheads + ", hedges=" + hedges
                + ", adaptiveLimits=" + adaptiveLimits + "]";
    }
}
