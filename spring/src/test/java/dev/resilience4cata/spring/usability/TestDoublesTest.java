package dev.resilience4cata.spring.usability;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.spring.Runners;
import dev.resilience4cata.spring.usability.fixtures.SpecialClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Hand-written test doubles of an annotated class, as a {@code @TestConfiguration} declares them. */
class TestDoublesTest {

    private final ApplicationContextRunner runner = Runners.runner();

    @Test
    void aStubSubclassFromABeanMethodFailsTheStartupWithR4cSpr001() {
        runner.withUserConfiguration(Stubs.class).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("R4C-SPR-001")
                    .hasMessageContaining("resilience4cata.spring.exclude");
        });
    }

    @Test
    void excludingTheStubsBeanNameKeepsItUnprotected() {
        runner.withUserConfiguration(Stubs.class).withPropertyValues("resilience4cata.spring.exclude=stubClient")
                .run(context -> assertThat(context.getBean(SpecialClient.class).fetch("1")).isEqualTo("stub"));
    }

    @Configuration(proxyBeanMethods = false)
    static class Stubs {

        @Bean
        SpecialClient stubClient() {
            return new SpecialClient() {
                @Override
                public String fetch(String id) {
                    return "stub";
                }
            };
        }
    }
}
