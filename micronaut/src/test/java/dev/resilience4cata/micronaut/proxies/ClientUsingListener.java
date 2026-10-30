package dev.resilience4cata.micronaut.proxies;

import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.BeanCreatedEvent;
import io.micronaut.context.event.BeanCreatedEventListener;
import jakarta.inject.Singleton;

/** An application's bean-created listener that depends on a protected bean, which Micronaut creates for it early. */
@Singleton
@Requires(env = "proxies")
public class ClientUsingListener implements BeanCreatedEventListener<PrototypeQuotes> {

    public final EarlyClient client;

    public ClientUsingListener(EarlyClient client) {
        this.client = client;
    }

    @Override
    public PrototypeQuotes onCreated(BeanCreatedEvent<PrototypeQuotes> event) {
        return event.getBean();
    }
}
