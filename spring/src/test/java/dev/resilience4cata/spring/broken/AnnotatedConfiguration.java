package dev.resilience4cata.spring.broken;

import dev.resilience4cata.annotation.Retry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** R4C-SPR-002: Spring subclasses a full {@code @Configuration} class with {@code @Bean} methods itself. */
@Configuration
public class AnnotatedConfiguration {

    @Bean
    String greeting() {
        return "hello";
    }

    @Retry
    public String value() {
        return "value";
    }
}
