package dev.resilience4cata.spring.autoconfigure;

import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Ticker;
import dev.resilience4cata.config.ResilienceConfig;
import dev.resilience4cata.event.EventListener;
import dev.resilience4cata.event.ResilienceEvent;
import dev.resilience4cata.spring.ResilienceRegistryCustomizer;
import dev.resilience4cata.spring.autoconfigure.ResilienceSpringProperties.VerifyInstances;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.ResolvableType;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;

/**
 * The registry and the bean wiring: a {@link ResilienceConfig} read from the {@code resilience4cata.*}
 * properties, a {@link ResilienceRegistry} built from it with the application's
 * {@link ResilienceRegistryCustomizer}s, {@link EventListener}s and {@link Ticker}, the generated subclasses
 * of annotated classes instantiated in their place, and beans of annotated interfaces wrapped.
 */
@AutoConfiguration
@EnableConfigurationProperties(ResilienceSpringProperties.class)
public final class ResilienceAutoConfiguration {

    /** Created by Spring. */
    public ResilienceAutoConfiguration() {
    }

    @Bean
    static ResilientClassesPostProcessor resilientClassesPostProcessor(Environment environment) {
        return new ResilientClassesPostProcessor(Set.copyOf(settings(environment).exclude()));
    }

    @Bean
    static ResilientBeansPostProcessor resilientBeansPostProcessor(Environment environment,
            ObjectProvider<ResilienceRegistry> registry) {
        ResilienceSpringProperties settings = settings(environment);
        return new ResilientBeansPostProcessor(registry::getObject, settings.wrap(), Set.copyOf(settings.exclude()));
    }

    /** Fails the startup when a transaction proxy goes around a retried method of an annotated class (R4C-SPR-004). */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.transaction.interceptor.TransactionInterceptor")
    static class TransactionCheckConfiguration {

        @Bean
        static RetryInsideTransactionCheck resilienceRetryInsideTransactionCheck(Environment environment) {
            ResilienceSpringProperties settings = settings(environment);
            return new RetryInsideTransactionCheck(settings.allowRetryInsideTransaction(), Set.copyOf(settings.exclude()));
        }
    }

    /** Post-processors exist before configuration properties beans do, so they bind the switches themselves. */
    private static ResilienceSpringProperties settings(Environment environment) {
        return Binder.get(environment).bindOrCreate(ResilienceSpringProperties.PREFIX, ResilienceSpringProperties.class);
    }

    /**
     * The policy configuration from the application's property sources, in Spring's precedence.
     *
     * @param environment the environment
     * @param resourceLoader for the class loader of exception classes
     * @return the configuration
     */
    @Bean
    @ConditionalOnMissingBean
    ResilienceConfig resilienceConfig(ConfigurableEnvironment environment, ResourceLoader resourceLoader) {
        return SpringEnvironmentConfig.read(environment, resourceLoader.getClassLoader());
    }

    /**
     * The registry: ticker, listeners and customizers first, then the configuration, which wins.
     *
     * @param config the configuration
     * @param customizers applied in order
     * @param ticker a clock for tests, such as the testkit's {@code ManualClock}
     * @param beanFactory to find the listener beans and their event types
     * @return the registry
     */
    @Bean
    @ConditionalOnMissingBean
    ResilienceRegistry resilienceRegistry(ResilienceConfig config,
            ObjectProvider<ResilienceRegistryCustomizer> customizers, ObjectProvider<Ticker> ticker,
            ConfigurableListableBeanFactory beanFactory) {
        ResilienceRegistry.Builder builder = ResilienceRegistry.builder();
        ticker.ifUnique(builder::ticker);
        registerListeners(builder, beanFactory);
        customizers.orderedStream().forEach(customizer -> customizer.customize(builder));
        config.applyTo(builder);
        return builder.build();
    }

    @Bean
    static InstanceVerifier resilienceInstanceVerifier(ObjectProvider<ResilienceConfig> config,
            ObjectProvider<ResilienceRegistry> registry, Environment environment) {
        VerifyInstances mode = settings(environment).verifyInstances();
        if (mode == null) {
            boolean lazy = environment.getProperty("spring.main.lazy-initialization", Boolean.class, false);
            mode = lazy ? VerifyInstances.WARN : VerifyInstances.FAIL;
        }
        return new InstanceVerifier(config, registry, mode);
    }

    /** Every {@link EventListener} bean, in {@code @Order}, for the event type in its generic signature. */
    private static void registerListeners(ResilienceRegistry.Builder builder, ConfigurableListableBeanFactory beanFactory) {
        Map<Object, String> names = new IdentityHashMap<>();
        for (String name : beanFactory.getBeanNamesForType(EventListener.class)) {
            names.put(beanFactory.getBean(name), name);
        }
        beanFactory.getBeanProvider(EventListener.class).orderedStream().forEach(listener ->
                listen(builder, eventType(listener, names.get(listener), beanFactory), listener));
    }

    /**
     * The most specific of the types declared by the listener's class and by its bean definition (the
     * return type of a {@code @Bean} method, which is all a lambda has), or {@link ResilienceEvent}.
     */
    private static Class<?> eventType(EventListener<?> listener, @Nullable String name,
            ConfigurableListableBeanFactory beanFactory) {
        Class<?> type = ResilienceEvent.class;
        Class<?> fromClass = ResolvableType.forClass(EventListener.class, listener.getClass()).getGeneric(0).resolve();
        if (fromClass != null && type.isAssignableFrom(fromClass)) {
            type = fromClass;
        }
        if (name != null && beanFactory.containsBeanDefinition(name)) {
            Class<?> fromDefinition = beanFactory.getMergedBeanDefinition(name).getResolvableType()
                    .as(EventListener.class).getGeneric(0).resolve();
            if (fromDefinition != null && type.isAssignableFrom(fromDefinition)) {
                type = fromDefinition;
            }
        }
        return type;
    }

    @SuppressWarnings({"unchecked", "rawtypes"}) // the type comes from the listener's own generic signature
    private static void listen(ResilienceRegistry.Builder builder, Class<?> type, EventListener<?> listener) {
        builder.listen((Class) type, (EventListener) listener);
    }
}
