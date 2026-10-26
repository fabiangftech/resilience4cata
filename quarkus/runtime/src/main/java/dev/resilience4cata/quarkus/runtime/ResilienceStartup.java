package dev.resilience4cata.quarkus.runtime;

import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.config.ConfigException;
import dev.resilience4cata.config.ResilienceConfig;
import dev.resilience4cata.quarkus.runtime.ResilienceRuntimeConfig.VerifyInstances;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;
import org.jboss.logging.Logger;

/**
 * Creates the registry when the application starts, so that a configuration error ({@code R4C-CFG-…} or
 * {@code R4C-REG-…}) stops the startup, and once the startup beans exist (the wrappers of interfaces whose beans
 * are normal-scoped among them) checks the {@code resilience4cata.instances.<id>.*} keys against the instances
 * created so far, as {@code quarkus.resilience4cata.verify-instances} says.
 */
@Singleton
public class ResilienceStartup {

    private static final Logger LOG = Logger.getLogger(ResilienceStartup.class);

    /** Created by ArC. */
    public ResilienceStartup() {
    }

    /**
     * Builds the registry, early in the startup.
     *
     * @param event the startup
     * @param registry the registry, created by this injection
     */
    void create(@Observes @Priority(1000) StartupEvent event, ResilienceRegistry registry) {
        LOG.debugf("resilience4cata registry ready: %s", registry);
    }

    /**
     * Checks the instance ids, after the startup beans.
     *
     * @param event the startup
     * @param config the configuration
     * @param registry the registry
     * @param settings the extension's run-time settings
     */
    void verify(@Observes @Priority(5000) StartupEvent event, ResilienceConfig config, ResilienceRegistry registry,
            ResilienceRuntimeConfig settings) {
        VerifyInstances mode = settings.verifyInstances();
        if (mode == VerifyInstances.OFF || config.instanceIds().isEmpty()) {
            return;
        }
        try {
            config.verifyInstances(registry);
        } catch (ConfigException e) {
            if (mode == VerifyInstances.FAIL) {
                throw e;
            }
            LOG.warn(e.getMessage() + "\n(quarkus.resilience4cata.verify-instances=warn: beans are created lazily, so"
                    + " the instances of annotated classes that are not @Startup beans, and of beans not injected yet,"
                    + " are not listed)");
        }
    }
}
