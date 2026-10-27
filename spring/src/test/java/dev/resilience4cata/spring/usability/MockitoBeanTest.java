package dev.resilience4cata.spring.usability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.resilience4cata.spring.usability.fixtures.Lookup;
import dev.resilience4cata.spring.usability.fixtures.NameLookup;
import dev.resilience4cata.spring.usability.fixtures.SpecialClient;
import dev.resilience4cata.testkit.ManualClock;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Component;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * {@code @MockitoBean} and {@code @MockitoSpyBean} on protected beans, as a Spring Boot test writes them. Uses
 * its own fixtures: Mockito's inline mock maker rewrites the classes it mocks for the whole JVM, which would
 * make other tests' allocation measurements fail.
 */
class MockitoBeanTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({NameLookup.class, SpecialClient.class, Consumer.class})
    static class App {

        @Bean
        ManualClock clock() {
            return new ManualClock();
        }
    }

    /** Uses both beans, as the class under test of a service test would. */
    @Component
    static class Consumer {

        final Lookup<String> names;
        final SpecialClient client;

        Consumer(Lookup<String> names, SpecialClient client) {
            this.names = names;
            this.client = client;
        }
    }

    /** A mocked annotated interface: the mock replaces the implementation, and is not wrapped. */
    @Nested
    @SpringBootTest(classes = App.class)
    class MockedInterface {

        @MockitoBean
        Lookup<String> names;

        @Autowired
        Consumer consumer;

        @Test
        void theConsumerGetsTheMockAsIsWithoutRetries() {
            when(names.find("a")).thenThrow(new IllegalStateException("stubbed"));

            assertThatThrownBy(() -> consumer.names.find("a")).hasMessage("stubbed");
            verify(names, times(1)).find("a");
            assertThat(consumer.names).isSameAs(names);
        }
    }

    /** A mocked annotated class: the mock replaces the generated subclass. */
    @Nested
    @SpringBootTest(classes = App.class)
    class MockedClass {

        @MockitoBean
        SpecialClient client;

        @Autowired
        Consumer consumer;

        @Test
        void theConsumerGetsTheMockAsIs() {
            when(client.fetch("a")).thenReturn("mocked");

            assertThat(consumer.client.fetch("a")).isEqualTo("mocked");
            assertThat(consumer.client).isSameAs(client);
        }
    }

    /** A spy on an annotated class: the spy is the generated subclass, so the policies still run. */
    @Nested
    @SpringBootTest(classes = App.class)
    class SpiedClass {

        @MockitoSpyBean
        SpecialClient client;

        @Autowired
        Consumer consumer;

        @Test
        void theSpyKeepsThePolicies() {
            client.failures.set(2);

            assertThat(consumer.client.fetch("a")).isEqualTo("fetched-a");
            assertThat(client.attempts).hasValue(3);
            verify(client).fetch("a");
        }
    }

    /** A spy on the implementation of an annotated interface. */
    @Nested
    @SpringBootTest(classes = App.class)
    class SpiedInterface {

        @MockitoSpyBean
        Lookup<String> names;

        @Autowired
        Consumer consumer;

        @Disabled("BUG-SPR-5: the spy is wrapped after Spring creates it, so the field holds the wrapper: NotAMockException")
        @Test
        void theSpyCanBeVerified() {
            assertThat(consumer.names.find("a")).isEqualTo("name-a");

            verify(names).find("a");
        }
    }
}
