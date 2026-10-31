package dev.resilience4cata.spring.autoconfigure;

import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.spring.ResilienceHealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.autoconfigure.contributor.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;

/**
 * The {@code resilience4cata} health indicator, when {@code spring-boot-health} is on the class path;
 * {@code management.health.resilience4cata.enabled=false} turns it off.
 */
@AutoConfiguration(after = ResilienceAutoConfiguration.class)
@ConditionalOnClass(HealthIndicator.class)
@ConditionalOnEnabledHealthIndicator("resilience4cata")
@EnableConfigurationProperties(ResilienceSpringProperties.class)
public final class ResilienceHealthAutoConfiguration {

    /** Created by Spring. */
    public ResilienceHealthAutoConfiguration() {
    }

    /**
     * The indicator, named {@code resilience4cata} after the bean.
     *
     * @param registry the registry
     * @param properties for {@code resilience4cata.spring.health.down-when-open}
     * @return the indicator
     */
    @Bean
    @ConditionalOnBean(ResilienceRegistry.class)
    @ConditionalOnMissingBean(name = "resilience4cataHealthIndicator")
    ResilienceHealthIndicator resilience4cataHealthIndicator(ResilienceRegistry registry,
            ResilienceSpringProperties properties) {
        return new ResilienceHealthIndicator(registry, properties.health().downWhenOpen());
    }
}
