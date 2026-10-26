package dev.resilience4cata.quarkus.runtime;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.util.List;
import java.util.Optional;

/** The extension's run-time settings, {@code quarkus.resilience4cata.*}. */
@ConfigMapping(prefix = "quarkus.resilience4cata")
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface ResilienceRuntimeConfig {

    /**
     * What to do at startup when a {@code resilience4cata.instances.<id>.*} key matches no policy instance
     * (R4C-CFG-013). Quarkus creates beans lazily, so only the instances of beans created by then exist: protected
     * interfaces of normal-scoped beans are wrapped at startup, but annotated classes are created on first use
     * unless they are {@code @Startup} beans. Hence {@code warn} by default.
     *
     * @return the mode
     */
    @WithDefault("warn")
    VerifyInstances verifyInstances();

    /**
     * The health check.
     *
     * @return the health settings
     */
    Health health();

    /** What a startup check of the instance ids does with an unknown id. */
    enum VerifyInstances {
        /** Fail the startup. */
        FAIL,
        /** Log a warning. */
        WARN,
        /** Do not check. */
        OFF
    }

    /** The health check. */
    interface Health {

        /**
         * The circuit breakers (instance ids, or {@code *} for all) that turn the {@code resilience4cata} readiness
         * check {@code DOWN} while open or forced open. By default none: an open breaker is the application
         * protecting itself, which is no reason to take it out of load balancing.
         *
         * @return the critical breakers
         */
        Optional<List<String>> downWhenOpen();
    }
}
