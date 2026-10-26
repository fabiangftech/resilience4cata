package dev.resilience4cata.micronaut;

import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.config.ConfigException;
import dev.resilience4cata.config.ResilienceConfig;
import dev.resilience4cata.micronaut.MicronautSettings.VerifyInstances;
import io.micronaut.context.BeanContext;
import io.micronaut.context.BeanProvider;
import io.micronaut.context.env.Environment;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.context.event.StartupEvent;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * At startup, checks that each {@code resilience4cata.instances.<id>.*} key matches a policy instance
 * ({@link ResilienceConfig#verifyInstances}): logs a warning (the default), fails the startup, or does nothing,
 * per {@code resilience4cata.micronaut.verify-instances}. Micronaut creates singletons lazily by default, so the
 * instances of beans not created yet are not known at startup: the default is {@code warn}, or {@code fail} when
 * the context creates its singletons eagerly ({@code eagerInitSingletons(true)}), as every instance exists then.
 */
@Singleton
final class InstanceVerifier implements ApplicationEventListener<StartupEvent> {

    private static final Logger LOG = LoggerFactory.getLogger(InstanceVerifier.class);

    private final BeanProvider<ResilienceConfig> config;
    private final BeanProvider<ResilienceRegistry> registry;
    private final VerifyInstances mode;

    InstanceVerifier(BeanProvider<ResilienceConfig> config, BeanProvider<ResilienceRegistry> registry,
            Environment environment, BeanContext context) {
        this.config = config;
        this.registry = registry;
        this.mode = MicronautSettings.of(environment, context.getContextConfiguration().isEagerInitSingletons())
                .verifyInstances();
    }

    @Override
    public void onApplicationEvent(StartupEvent event) {
        if (mode == VerifyInstances.OFF || !config.isUnique() || !registry.isUnique()) {
            return;
        }
        ResilienceConfig resilienceConfig = config.get();
        if (resilienceConfig.instanceIds().isEmpty()) {
            return;
        }
        try {
            resilienceConfig.verifyInstances(registry.get());
        } catch (ConfigException e) {
            if (mode == VerifyInstances.FAIL) {
                throw e;
            }
            LOG.warn("{}\n({}.verify-instances=warn: Micronaut creates beans lazily, so the instances of beans created"
                    + " after startup are not listed yet)", e.getMessage(), MicronautSettings.PREFIX);
        }
    }
}
