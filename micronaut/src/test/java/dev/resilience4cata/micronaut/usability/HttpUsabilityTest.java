package dev.resilience4cata.micronaut.usability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micronaut.context.ApplicationContext;
import io.micronaut.http.client.HttpClient;
import io.micronaut.runtime.server.EmbeddedServer;
import java.util.Map;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/** A declarative {@code @Client} and an annotated {@code @Controller}, over a real server. */
class HttpUsabilityTest {

    @Test
    void aDeclarativeClientMarkedResilientBeanIsRetried() {
        try (EmbeddedServer server = ApplicationContext.run(EmbeddedServer.class,
                Map.of("micronaut.server.port", "-1", "test.usability", "http"))) {
            ApplicationContext context = server.getApplicationContext();
            GreetingClient client = context.getBean(GreetingClient.class);
            assertThat(client.getClass().getSimpleName()).isEqualTo("GreetingClient$Resilient");
            Failures failures = context.getBean(Failures.class);

            failures.failNext(2);
            assertThat(client.greeting()).isEqualTo("hello");
            assertThat(failures.calls()).as("two failed requests, then a successful one").isEqualTo(3);
        }
    }

    @Test
    void anAnnotatedControllerServesItsRoutesThroughThePolicies() {
        try (EmbeddedServer server = ApplicationContext.run(EmbeddedServer.class,
                Map.of("micronaut.server.port", "-1", "test.usability", "http"));
                HttpClient client = server.getApplicationContext().createBean(HttpClient.class, server.getURL())) {
            Failures failures = server.getApplicationContext().getBean(Failures.class);
            failures.failNext(2);
            assertThat(client.toBlocking().retrieve("/usability/price/a?currency=EUR")).isEqualTo("a=7 EUR");
            assertThat(failures.calls()).isEqualTo(3);
        }
    }

    @Test
    @Disabled("BUG-MN-5: generated overrides rename parameters to p0, p1..., so @PathVariable without a name gets 400")
    void aPathVariableIsBoundByTheParameterNameAsInTheClass() {
        try (EmbeddedServer server = ApplicationContext.run(EmbeddedServer.class,
                Map.of("micronaut.server.port", "-1", "test.usability", "http"));
                HttpClient client = server.getApplicationContext().createBean(HttpClient.class, server.getURL())) {
            Failures failures = server.getApplicationContext().getBean(Failures.class);
            failures.failNext(2);
            assertThat(client.toBlocking().retrieve("/usability/stock/a")).isEqualTo("a=42");
            assertThat(failures.calls()).isEqualTo(3);
        }
    }
}
