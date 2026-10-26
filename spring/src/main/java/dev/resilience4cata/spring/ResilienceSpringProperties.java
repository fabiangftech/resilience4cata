package dev.resilience4cata.spring;

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The switches of the Spring integration itself, under {@code resilience4cata.spring}. Every other
 * {@code resilience4cata.*} key is policy configuration, read by {@code resilience4cata-config}.
 *
 * <p>The parameter descriptions are plain sentences: Spring Boot's configuration processor copies them into
 * the IDE metadata.
 *
 * @param wrap Whether beans that implement an annotated interface are replaced by its generated wrapper.
 *     Annotated classes are always instantiated as their generated subclass.
 * @param exclude Names of beans to leave alone: neither instantiated as a generated subclass, wrapped, nor
 *     checked.
 * @param verifyInstances What to do at startup when a resilience4cata.instances key matches no policy
 *     instance: fail (the default), warn (the default with lazy initialization, where instances appear
 *     later), or off.
 * @param allowRetryInsideTransaction Whether an annotated class may retry or hedge a transactional method: every
 *     attempt then runs in the same transaction. Off by default: the startup fails instead.
 * @param health The health indicator.
 * @param metrics The Micrometer metrics.
 */
@ConfigurationProperties("resilience4cata.spring")
public record ResilienceSpringProperties(@DefaultValue("true") boolean wrap, @DefaultValue List<String> exclude,
        @Nullable VerifyInstances verifyInstances, @DefaultValue("false") boolean allowRetryInsideTransaction,
        @DefaultValue Health health, @DefaultValue Metrics metrics) {

    /** The properties' prefix: {@value}. */
    public static final String PREFIX = "resilience4cata.spring";

    /** Copies the list. */
    public ResilienceSpringProperties {
        exclude = List.copyOf(exclude);
    }

    /**
     * The values used when no {@code resilience4cata.spring.*} key is set.
     *
     * @return the defaults
     */
    public static ResilienceSpringProperties defaults() {
        return new ResilienceSpringProperties(true, List.of(), null, false, new Health(List.of()), new Metrics(true));
    }

    /** What to do with per-instance overrides that match no instance. */
    public enum VerifyInstances {
        /** Fail the startup with {@code R4C-CFG-013}. */
        FAIL,
        /** Log a warning with {@code R4C-CFG-013}. */
        WARN,
        /** Do not check. */
        OFF
    }

    /**
     * The health indicator ({@code management.health.resilience4cata.enabled} turns it off).
     *
     * @param downWhenOpen Circuit breaker ids that make the indicator DOWN while open or forced open, and
     *     UNKNOWN while half open, or * for every breaker. Empty by default: an open breaker protects the
     *     application from a failing dependency, so the indicator stays UP.
     */
    public record Health(@DefaultValue List<String> downWhenOpen) {

        /** Copies the list. */
        public Health {
            downWhenOpen = List.copyOf(downWhenOpen);
        }
    }

    /**
     * The Micrometer metrics, registered when {@code resilience4cata-micrometer} is on the class path and
     * the application has a {@code MeterRegistry}.
     *
     * @param enabled Whether to register the metrics.
     */
    public record Metrics(@DefaultValue("true") boolean enabled) {
    }
}
