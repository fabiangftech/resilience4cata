package dev.resilience4cata.spring;

import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.ResilienceSnapshot;
import java.util.Map;
import java.util.Objects;
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
     * Returns the state of every policy instance, as {@link ResilienceSnapshot#toMap()}.
     *
     * @return the registry's snapshot
     */
    @ReadOperation
    public Map<String, @Nullable Object> snapshot() {
        return registry.snapshot().toMap();
    }

    /**
     * Returns the state of the policies of one instance id, a policy's {@code name} or the derived
     * {@code <type>#<method>(<parameter types>)}, as {@link ResilienceSnapshot#toMap()}.
     *
     * @param instance the instance id
     * @return the snapshot restricted to that id, or {@code null} (404) when no policy has it
     */
    @ReadOperation
    public @Nullable Map<String, @Nullable Object> instance(@Selector String instance) {
        ResilienceSnapshot one = registry.snapshot().only(instance);
        return one.isEmpty() ? null : one.toMap();
    }
}
