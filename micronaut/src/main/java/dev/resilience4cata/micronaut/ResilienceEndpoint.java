package dev.resilience4cata.micronaut;

import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.ResilienceSnapshot;
import io.micronaut.context.annotation.Requires;
import io.micronaut.management.endpoint.annotation.Endpoint;
import io.micronaut.management.endpoint.annotation.Read;
import io.micronaut.management.endpoint.annotation.Selector;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The read-only {@code resilience4cata} management endpoint: {@link ResilienceRegistry#snapshot()} of every policy
 * instance ({@code GET /resilience4cata}), or of one instance id ({@code GET /resilience4cata/{instance}}), when
 * {@code micronaut-management} is on the class path. Like Micronaut's own endpoints it is sensitive by default:
 * enable and expose it with {@code endpoints.resilience4cata.enabled} and {@code endpoints.resilience4cata.sensitive}.
 * It reads snapshots when queried and keeps no history.
 */
@Endpoint(id = "resilience4cata", defaultSensitive = true)
@Requires(classes = Endpoint.class)
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
     * @return the registry's snapshot, one list per policy kind
     */
    @Read
    public Map<String, @Nullable Object> snapshot() {
        return registry.snapshot().toMap();
    }

    /**
     * Returns the state of the policies of one instance id, a policy's {@code name} or the derived
     * {@code <type>#<method>(<parameter types>)}.
     *
     * @param instance the instance id
     * @return the snapshot restricted to that id, or {@code null} (404) when no policy has it
     */
    @Read
    public @Nullable Map<String, @Nullable Object> instance(@Selector String instance) {
        ResilienceSnapshot one = registry.snapshot().only(instance);
        return one.isEmpty() ? null : one.toMap();
    }
}
