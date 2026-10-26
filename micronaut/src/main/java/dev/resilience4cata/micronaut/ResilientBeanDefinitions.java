package dev.resilience4cata.micronaut;

import io.micronaut.context.BeanContext;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.context.event.StartupEvent;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.qualifiers.Qualifiers;
import jakarta.inject.Singleton;

/**
 * At startup, checks every bean definition annotated with {@link ResilientBean}
 * ({@link ResilientBeansListener#validate}), so that a mistake stops the startup even when the bean is created
 * lazily, and before Micronaut's own proxies of a scoped bean fail with a less helpful message. Reads bean
 * definitions only: no bean is created.
 */
@Singleton
final class ResilientBeanDefinitions implements ApplicationEventListener<StartupEvent> {

    private final BeanContext context;

    ResilientBeanDefinitions(BeanContext context) {
        this.context = context;
    }

    @Override
    public void onApplicationEvent(StartupEvent event) {
        for (BeanDefinition<Object> definition : context.getBeanDefinitions(Qualifiers.byStereotype(ResilientBean.class))) {
            Class<?>[] declared = definition.classValues(ResilientBean.class);
            if (declared.length > 0) {
                ResilientBeansListener.validate(definition, declared[0]);
            }
        }
    }
}
