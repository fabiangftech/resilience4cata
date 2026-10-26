package dev.resilience4cata.quarkus.guide;

import dev.resilience4cata.event.CircuitBreakerEvent;
import dev.resilience4cata.event.EventListener;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/** The guide's listener bean. */
@Singleton
public class Listeners {

    // tag::quarkus-listener[]
    @Produces
    @Singleton
    EventListener<CircuitBreakerEvent.StateTransition> breakerAlerts(Alerts alerts) {
        return transition -> alerts.send(transition.instance() + " is now " + transition.to());
    }
    // end::quarkus-listener[]
}
