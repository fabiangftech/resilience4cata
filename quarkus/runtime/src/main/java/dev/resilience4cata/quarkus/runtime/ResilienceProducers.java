package dev.resilience4cata.quarkus.runtime;

import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Ticker;
import dev.resilience4cata.config.ResilienceConfig;
import dev.resilience4cata.event.EventListener;
import dev.resilience4cata.event.ResilienceEvent;
import dev.resilience4cata.quarkus.ResilienceRegistryCustomizer;
import io.quarkus.arc.All;
import io.quarkus.arc.DefaultBean;
import io.quarkus.arc.InstanceHandle;
import io.smallrye.config.SmallRyeConfig;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import org.eclipse.microprofile.config.ConfigProvider;

/**
 * The {@link ResilienceConfig} and {@link ResilienceRegistry} beans. Both are {@link DefaultBean}s: an application
 * that produces its own wins. The registry is built from the application's {@link Ticker} bean (if exactly one),
 * its {@link EventListener} beans, its {@link ResilienceRegistryCustomizer}s in priority order, and then the
 * configuration, which wins. It is created at startup ({@link ResilienceStartup}), so a configuration error stops
 * the application before it serves anything.
 */
@Singleton
public class ResilienceProducers {

    /** Created by ArC. */
    public ResilienceProducers() {
    }

    /**
     * The policy configuration, read from SmallRye Config.
     *
     * @return the configuration
     */
    @Produces
    @Singleton
    @DefaultBean
    public ResilienceConfig resilienceConfig() {
        SmallRyeConfig config = ConfigProvider.getConfig().unwrap(SmallRyeConfig.class);
        return QuarkusConfigReader.read(config, Thread.currentThread().getContextClassLoader());
    }

    /**
     * The registry.
     *
     * @param config the configuration
     * @param customizers applied in priority order
     * @param listeners registered for the event type in their bean types
     * @param ticker a clock for tests, such as the testkit's {@code ManualClock}
     * @param bound told about the registry once it is built
     * @return the registry
     */
    @Produces
    @Singleton
    @DefaultBean
    public ResilienceRegistry resilienceRegistry(ResilienceConfig config,
            @All List<ResilienceRegistryCustomizer> customizers,
            @All List<InstanceHandle<EventListener<?>>> listeners, Instance<Ticker> ticker,
            @All List<RegistryBound> bound) {
        ResilienceRegistry.Builder builder = ResilienceRegistry.builder();
        if (ticker.isResolvable()) {
            builder.ticker(ticker.get());
        }
        for (InstanceHandle<EventListener<?>> listener : listeners) {
            listen(builder, eventType(listener.getBean().getTypes()), listener.get());
        }
        customizers.forEach(customizer -> customizer.customize(builder));
        config.applyTo(builder);
        ResilienceRegistry registry = builder.build();
        bound.forEach(b -> b.bind(registry));
        return registry;
    }

    /** The most specific event type among the listener's bean types {@code EventListener<E>}, or {@link ResilienceEvent}. */
    static Class<?> eventType(Iterable<Type> types) {
        Class<?> event = ResilienceEvent.class;
        for (Type type : types) {
            if (type instanceof ParameterizedType parameterized && parameterized.getRawType() == EventListener.class
                    && parameterized.getActualTypeArguments()[0] instanceof Class<?> argument
                    && event.isAssignableFrom(argument)) {
                event = argument;
            }
        }
        return event;
    }

    @SuppressWarnings({"unchecked", "rawtypes"}) // the type comes from the listener's own bean types
    private static void listen(ResilienceRegistry.Builder builder, Class<?> type, EventListener<?> listener) {
        builder.listen((Class) type, (EventListener) listener);
    }
}
