package dev.resilience4cata.quarkus.runtime;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.util.List;
import java.util.Optional;

/** The extension's build-time switches, {@code quarkus.resilience4cata.*}; the policies are configured under {@code resilience4cata.*}. */
@ConfigMapping(prefix = "quarkus.resilience4cata")
@ConfigRoot(phase = ConfigPhase.BUILD_TIME)
public interface ResilienceBuildTimeConfig {

    /**
     * Beans and the generated classes.
     *
     * @return the bean settings
     */
    Beans beans();

    /**
     * The health check.
     *
     * @return the health settings
     */
    Health health();

    /**
     * Metrics.
     *
     * @return the metrics settings
     */
    Metrics metrics();

    /**
     * Whether a retried or hedged method of an annotated class may be {@code @Transactional}. Its transaction
     * interceptor goes around the generated subclass, so every attempt runs in one transaction; by default this
     * fails the build (R4C-QK-002).
     *
     * @return whether one transaction around all attempts is intended
     */
    @WithDefault("false")
    boolean allowRetryInsideTransaction();

    /** Beans and the generated classes. */
    interface Beans {

        /**
         * Whether beans are protected: a bean implementing an annotated interface is injected through its generated
         * wrapper, and the generated subclass of an annotated class replaces the class as the bean.
         *
         * @return whether beans are protected
         */
        @WithDefault("true")
        boolean enabled();

        /**
         * Bean classes (fully qualified names) left as they are: not wrapped, not replaced.
         *
         * @return the excluded classes
         */
        Optional<List<String>> exclude();
    }

    /** The health check. */
    interface Health {

        /**
         * Whether the {@code resilience4cata} readiness check is registered, when SmallRye Health is present.
         *
         * @return whether the check is registered
         */
        @WithDefault("true")
        boolean enabled();
    }

    /** Metrics. */
    interface Metrics {

        /**
         * Whether the registry is bound to Micrometer (when {@code quarkus-micrometer} and
         * {@code resilience4cata-micrometer} are present) or else OpenTelemetry (when {@code quarkus-opentelemetry}
         * and {@code resilience4cata-opentelemetry} are present).
         *
         * @return whether metrics are bound
         */
        @WithDefault("true")
        boolean enabled();
    }
}
