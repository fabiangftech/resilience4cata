package dev.resilience4cata.micronaut;

import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Ticker;
import dev.resilience4cata.config.ResilienceConfig;
import dev.resilience4cata.event.EventListener;
import dev.resilience4cata.event.ResilienceEvent;
import io.micronaut.context.BeanContext;
import io.micronaut.context.BeanProvider;
import io.micronaut.context.BeanRegistration;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Prototype;
import io.micronaut.context.env.Environment;
import io.micronaut.core.order.OrderUtil;
import io.micronaut.core.type.Argument;
import java.util.ArrayList;
import java.util.List;

/**
 * The registry: a {@link ResilienceConfig} read from the application's configuration, a
 * {@link ResilienceRegistry.Builder} with the application's {@link Ticker} and {@link EventListener} beans, and the
 * registry built from it once every {@code BeanCreatedEventListener<ResilienceRegistry.Builder>} has customized
 * the builder. The configuration is applied last, so it wins over code. Both the configuration and the registry
 * are {@link Context} beans: an invalid key stops the startup. An application replaces either with
 * {@code @Replaces(ResilienceRegistry.class)} (or {@code ResilienceConfig.class}) on its own bean.
 */
@Factory
final class ResilienceFactory {

    @Context
    ResilienceConfig resilienceConfig(Environment environment) {
        return MicronautEnvironmentConfig.read(environment);
    }

    /**
     * A new builder each time it is injected, so that {@code BeanCreatedEventListener}s can customize it (the
     * Micronaut way to customize a bean such as Jackson's {@code ObjectMapper}).
     */
    @Prototype
    ResilienceRegistry.Builder resilienceRegistryBuilder(BeanProvider<Ticker> ticker, BeanContext context) {
        ResilienceRegistry.Builder builder = ResilienceRegistry.builder();
        if (ticker.isUnique()) {
            builder.ticker(ticker.get());
        }
        registerListeners(builder, context);
        return builder;
    }

    @Context
    ResilienceRegistry resilienceRegistry(ResilienceRegistry.Builder builder, ResilienceConfig config) {
        return config.applyTo(builder).build();
    }

    /** Every {@link EventListener} bean, in {@code @Order}, for the event type of its bean definition. */
    @SuppressWarnings({"rawtypes", "unchecked"}) // the type comes from the listener's own generic signature
    private static void registerListeners(ResilienceRegistry.Builder builder, BeanContext context) {
        List<BeanRegistration<EventListener>> registrations =
                new ArrayList<>(context.getBeanRegistrations(EventListener.class));
        registrations.sort(OrderUtil.COMPARATOR);
        for (BeanRegistration<EventListener> registration : registrations) {
            List<Argument<?>> arguments = registration.getBeanDefinition().getTypeArguments(EventListener.class);
            Class<?> type = arguments.isEmpty() ? ResilienceEvent.class : arguments.get(0).getType();
            if (!ResilienceEvent.class.isAssignableFrom(type)) {
                type = ResilienceEvent.class;
            }
            builder.listen((Class) type, registration.getBean());
        }
    }
}
