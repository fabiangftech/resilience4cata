package dev.resilience4cata.spring.autoconfigure;

import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.config.ConfigException;
import dev.resilience4cata.config.ResilienceConfig;
import dev.resilience4cata.spring.autoconfigure.ResilienceSpringProperties.VerifyInstances;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;

/**
 * Once every eager singleton exists (so every protected bean has created its policy instances), checks
 * that each {@code resilience4cata.instances.<id>.*} key matches an instance
 * ({@link ResilienceConfig#verifyInstances}): fails the startup or logs a warning.
 */
final class InstanceVerifier implements SmartInitializingSingleton {

    private static final Log LOG = LogFactory.getLog(InstanceVerifier.class);

    private final ObjectProvider<ResilienceConfig> config;
    private final ObjectProvider<ResilienceRegistry> registry;
    private final VerifyInstances mode;

    InstanceVerifier(ObjectProvider<ResilienceConfig> config, ObjectProvider<ResilienceRegistry> registry,
            VerifyInstances mode) {
        this.config = config;
        this.registry = registry;
        this.mode = mode;
    }

    @Override
    public void afterSingletonsInstantiated() {
        ResilienceConfig resilienceConfig = config.getIfUnique();
        ResilienceRegistry resilienceRegistry = registry.getIfUnique();
        if (mode == VerifyInstances.OFF || resilienceConfig == null || resilienceRegistry == null
                || resilienceConfig.instanceIds().isEmpty()) {
            return;
        }
        try {
            resilienceConfig.verifyInstances(resilienceRegistry);
        } catch (ConfigException e) {
            if (mode == VerifyInstances.FAIL) {
                throw e;
            }
            LOG.warn(e.getMessage() + "\n(resilience4cata.spring.verify-instances=warn; instances created after"
                    + " startup, by lazy beans or pipelines, are not listed yet)");
        }
    }
}
