package dev.resilience4cata.spring.guide;

import dev.resilience4cata.event.CircuitBreakerEvent;
import dev.resilience4cata.event.EventListener;
import dev.resilience4cata.spring.ResilienceRegistryCustomizer;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The guide's customizer and listener beans. */
@Configuration(proxyBeanMethods = false)
public class ResilienceSetup {

    // tag::spring-customizer[]
    @Bean
    ResilienceRegistryCustomizer slowBackends() {
        return builder -> builder.retry("slow", retry -> retry.maxAttempts(5).delay(Duration.ofSeconds(1)));
    }
    // end::spring-customizer[]

    // tag::spring-listener[]
    @Bean
    EventListener<CircuitBreakerEvent.StateTransition> breakerAlerts(Alerts alerts) {
        return transition -> alerts.send(transition.id() + " is now " + transition.to());
    }
    // end::spring-listener[]
}
