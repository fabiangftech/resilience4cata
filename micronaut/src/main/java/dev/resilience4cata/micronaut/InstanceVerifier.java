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
import io.micronaut.inject.AdvisedBeanType;
import io.micronaut.inject.BeanDefinition;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * At startup, checks that each {@code resilience4cata.instances.<id>.*} key matches a policy instance
 * ({@link ResilienceConfig#verifyInstances}): logs a warning (the default), fails the startup, or does nothing,
 * per {@code resilience4cata.micronaut.verify-instances}. Micronaut creates singletons lazily by default, so the
 * instances of beans not created yet are not known at startup: the default is {@code warn}, or {@code fail} when
 * the context creates its singletons eagerly ({@code eagerInitSingletons(true)}), as every instance exists then,
 * unless protected beans that are not singletons ({@code @Prototype}, {@code @Refreshable}, request scope) create
 * theirs later.
 */
@Singleton
final class InstanceVerifier implements ApplicationEventListener<StartupEvent> {

    private static final Logger LOG = LoggerFactory.getLogger(InstanceVerifier.class);

    private final BeanProvider<ResilienceConfig> config;
    private final BeanProvider<ResilienceRegistry> registry;
    private final BeanContext context;
    private final VerifyInstances configured;
    private final boolean explicit;

    InstanceVerifier(BeanProvider<ResilienceConfig> config, BeanProvider<ResilienceRegistry> registry,
            Environment environment, BeanContext context) {
        this.config = config;
        this.registry = registry;
        this.context = context;
        this.configured = MicronautSettings.of(environment, context.getContextConfiguration().isEagerInitSingletons())
                .verifyInstances();
        this.explicit = environment.containsProperty(MicronautSettings.PREFIX + ".verify-instances");
    }

    @Override
    public void onApplicationEvent(StartupEvent event) {
        VerifyInstances mode = configured == VerifyInstances.FAIL && !explicit && protectedBeansCreatedLater()
                ? VerifyInstances.WARN : configured;
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
            LOG.warn("{}\n({}.verify-instances=warn: the instances of beans created after startup, lazily or because"
                    + " they are not singletons, are not listed yet)", e.getMessage(), MicronautSettings.PREFIX);
        }
    }

    /** Whether a protected bean that is not a singleton exists: its instances appear when it is created. */
    private boolean protectedBeansCreatedLater() {
        GeneratedClasses generated = new GeneratedClasses();
        for (BeanDefinition<?> definition : context.getAllBeanDefinitions()) {
            if (definition.isSingleton()) {
                continue;
            }
            Class<?> type = definition instanceof AdvisedBeanType<?> advised ? advised.getInterceptedType()
                    : definition.getBeanType();
            if (generated.isGeneratedSubclass(type) || ResilientBeansListener.resilientBean(definition).length > 0) {
                return true;
            }
        }
        return false;
    }
}
