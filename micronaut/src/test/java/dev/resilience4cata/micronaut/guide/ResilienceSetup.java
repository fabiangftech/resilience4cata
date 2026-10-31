package dev.resilience4cata.micronaut.guide;

import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.event.CircuitBreakerEvent;
import dev.resilience4cata.event.EventListener;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.BeanCreatedEventListener;
import jakarta.inject.Singleton;
import java.time.Duration;

/** The registry customizations of the guide. */
@Factory
@Requires(env = "guide")
public class ResilienceSetup {

    // tag::micronaut-customizer[]
    @Singleton
    BeanCreatedEventListener<ResilienceRegistry.Builder> slowBackends() {
        return event -> event.getBean().retry("slow", retry -> retry.maxAttempts(5).delay(Duration.ofSeconds(1)));
    }
    // end::micronaut-customizer[]

    // tag::micronaut-listener[]
    @Singleton
    EventListener<CircuitBreakerEvent.StateTransition> breakerAlerts(Alerts alerts) {
        return transition -> alerts.send(transition.id() + " is now " + transition.to());
    }
    // end::micronaut-listener[]
}
