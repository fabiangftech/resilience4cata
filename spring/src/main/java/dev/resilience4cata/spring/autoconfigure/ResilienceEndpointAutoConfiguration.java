package dev.resilience4cata.spring.autoconfigure;

import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.spring.ResilienceEndpoint;
import org.springframework.boot.actuate.autoconfigure.endpoint.condition.ConditionalOnAvailableEndpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * The read-only {@code resilience4cata} actuator endpoint, when Spring Boot Actuator is on the class path
 * and the endpoint is available (exposed, for example with
 * {@code management.endpoints.web.exposure.include=resilience4cata}).
 */
@AutoConfiguration(after = ResilienceAutoConfiguration.class)
@ConditionalOnClass(name = {"org.springframework.boot.actuate.endpoint.annotation.Endpoint",
        "org.springframework.boot.actuate.autoconfigure.endpoint.condition.ConditionalOnAvailableEndpoint"})
@ConditionalOnAvailableEndpoint(ResilienceEndpoint.class)
public final class ResilienceEndpointAutoConfiguration {

    /** Created by Spring. */
    public ResilienceEndpointAutoConfiguration() {
    }

    /**
     * The endpoint.
     *
     * @param registry the registry
     * @return the endpoint
     */
    @Bean
    @ConditionalOnBean(ResilienceRegistry.class)
    @ConditionalOnMissingBean
    public ResilienceEndpoint resilienceEndpoint(ResilienceRegistry registry) {
        return new ResilienceEndpoint(registry);
    }
}
