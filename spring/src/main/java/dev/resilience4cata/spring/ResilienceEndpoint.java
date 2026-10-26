package dev.resilience4cata.spring;

import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.ResilienceSnapshot;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.actuate.endpoint.Access;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;

/**
 * The read-only {@code resilience4cata} actuator endpoint: {@link ResilienceRegistry#snapshot()} of every
 * policy instance ({@code GET /actuator/resilience4cata}), or of one instance id
 * ({@code GET /actuator/resilience4cata/{instance}}). Expose it like any endpoint, for example with
 * {@code management.endpoints.web.exposure.include=health,resilience4cata}. It reads snapshots when
 * queried and keeps no history.
 */
@Endpoint(id = "resilience4cata", defaultAccess = Access.READ_ONLY)
public final class ResilienceEndpoint {

    private final ResilienceRegistry registry;

    /**
     * Creates the endpoint.
     *
     * @param registry the registry to report
     */
    public ResilienceEndpoint(ResilienceRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /**
     * Returns the state of every policy instance.
     *
     * @return the registry's snapshot
     */
    @ReadOperation
    public ResilienceSnapshot snapshot() {
        return registry.snapshot();
    }

    /**
     * Returns the state of the policies of one instance id, a policy's {@code name} or the derived
     * {@code <type>#<method>(<parameter types>)}.
     *
     * @param instance the instance id
     * @return the snapshot restricted to that id, or {@code null} (404) when no policy has it
     */
    @ReadOperation
    public @Nullable ResilienceSnapshot instance(@Selector String instance) {
        ResilienceSnapshot all = registry.snapshot();
        ResilienceSnapshot one = new ResilienceSnapshot(only(all.circuitBreakers(), instance, s -> s.instance()),
                only(all.retries(), instance, s -> s.instance()), only(all.rateLimiters(), instance, s -> s.instance()),
                only(all.timeouts(), instance, s -> s.instance()), only(all.bulkheads(), instance, s -> s.instance()),
                only(all.hedges(), instance, s -> s.instance()), only(all.adaptiveLimits(), instance, s -> s.instance()));
        boolean empty = one.circuitBreakers().isEmpty() && one.retries().isEmpty() && one.rateLimiters().isEmpty()
                && one.timeouts().isEmpty() && one.bulkheads().isEmpty() && one.hedges().isEmpty()
                && one.adaptiveLimits().isEmpty();
        return empty ? null : one;
    }

    private static <S> List<S> only(List<S> snapshots, String instance, Function<S, String> id) {
        return snapshots.stream().filter(s -> id.apply(s).equals(instance)).toList();
    }
}
