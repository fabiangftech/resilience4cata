package dev.resilience4cata.spring.httpservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import dev.resilience4cata.CallTimeoutException;
import dev.resilience4cata.CircuitBreakerState;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.spring.autoconfigure.ResilienceAutoConfiguration;
import dev.resilience4cata.spring.httpservice.PriceBackend.Answer;
import java.io.IOException;
import java.time.Duration;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.http.client.autoconfigure.imperative.ImperativeHttpClientAutoConfiguration;
import org.springframework.boot.http.client.autoconfigure.service.HttpServiceClientPropertiesAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.service.HttpServiceClientAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.service.registry.ImportHttpServices;

/**
 * Spring's declarative HTTP clients ({@code @HttpExchange} interfaces registered with {@code @ImportHttpServices}) are
 * the most direct use of the interface style: Spring creates the implementation as a JDK proxy, and the integration
 * must wrap that proxy in the generated class. Here against a real HTTP server (the JDK's), on the real clock, through
 * Boot's own auto-configuration of the HTTP service groups ({@code spring-boot-restclient}).
 */
class HttpServiceClientTest {

    private static final String ID = "dev.resilience4cata.spring.httpservice.PriceService#price(java.lang.String)";

    private final PriceBackend backend;

    HttpServiceClientTest() throws IOException {
        backend = new PriceBackend();
    }

    @AfterEach
    void stopBackend() {
        backend.close();
    }

    @Configuration(proxyBeanMethods = false)
    @ImportHttpServices(group = "prices", types = PriceService.class)
    static class Clients {
    }

    private void run(String baseUrl, Consumer<AssertableApplicationContext> test) {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ResilienceAutoConfiguration.class, HttpClientAutoConfiguration.class,
                        ImperativeHttpClientAutoConfiguration.class, HttpServiceClientPropertiesAutoConfiguration.class,
                        RestClientAutoConfiguration.class, HttpServiceClientAutoConfiguration.class))
                .withUserConfiguration(Clients.class)
                .withPropertyValues("spring.http.serviceclient.prices.base-url=" + baseUrl)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    test.accept(context);
                });
    }

    @Test
    void theHttpServiceProxyIsWrappedInTheGeneratedClass() {
        run(backend.baseUrl(), context -> {
            PriceService prices = context.getBean(PriceService.class);

            assertThat(prices.getClass().getName()).isEqualTo(PriceService.class.getName() + "$Resilient");
            assertThat(prices.price("abc")).isEqualTo("42");
            assertThat(backend.requests()).isOne();
        });
    }

    @Test
    void serverErrorsAreRetried() {
        backend.answer(Answer.of(503, "busy"), Answer.of(503, "busy"), Answer.of(200, "42"));

        run(backend.baseUrl(), context -> {
            assertThat(context.getBean(PriceService.class).price("abc")).isEqualTo("42");
            assertThat(backend.requests()).isEqualTo(3);
        });
    }

    @Test
    void clientErrorsAreNeitherRetriedNorRecorded() {
        backend.answer(Answer.of(404, "no such sku"));

        run(backend.baseUrl(), context -> {
            PriceService prices = context.getBean(PriceService.class);
            for (int i = 0; i < 5; i++) {
                assertThat(catchThrowable(() -> prices.price("nope"))).isInstanceOf(HttpClientErrorException.NotFound.class);
            }
            assertThat(backend.requests()).isEqualTo(5);
            assertThat(context.getBean(ResilienceRegistry.class).circuitBreaker(ID).state())
                    .isEqualTo(CircuitBreakerState.CLOSED);
        });
    }

    @Test
    void anUnreachableBackendIsRetriedThenTheDefaultMethodFallbackAnswers() {
        String baseUrl = backend.baseUrl();
        backend.close(); // nothing listens on that port any more: connection refused

        run(baseUrl, context -> assertThat(context.getBean(PriceService.class).price("abc")).isEqualTo("unknown"));
    }

    @Test
    void theBreakerOpensOnServerErrorsAndStopsTheRequests() {
        backend.answer(Answer.of(503, "down"));

        run(backend.baseUrl(), context -> {
            PriceService prices = context.getBean(PriceService.class);

            // Three attempts, three recorded failures, below minimumCalls: the 503 reaches the caller.
            assertThat(catchThrowable(() -> prices.price("abc"))).isInstanceOf(HttpServerErrorException.ServiceUnavailable.class);
            assertThat(backend.requests()).isEqualTo(3);
            // The fourth failure opens the breaker; the retry does not retry into it and the fallback answers.
            assertThat(prices.price("abc")).isEqualTo("breaker open");
            assertThat(backend.requests()).isEqualTo(4);
            assertThat(prices.price("abc")).isEqualTo("breaker open");
            assertThat(backend.requests()).isEqualTo(4);
            assertThat(context.getBean(ResilienceRegistry.class).circuitBreaker(ID).state())
                    .isEqualTo(CircuitBreakerState.OPEN);
        });
    }

    @Test
    void aSlowAnswerIsCutOffByTheTimeout() {
        backend.answer(new Answer(200, "late", Duration.ofSeconds(30)));

        run(backend.baseUrl(), context -> {
            long start = System.nanoTime();
            Throwable thrown = catchThrowable(() -> context.getBean(PriceService.class).price("abc"));

            assertThat(thrown).isInstanceOf(CallTimeoutException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isBetween(Duration.ofSeconds(3), Duration.ofSeconds(25));
            assertThat(backend.requests()).isOne(); // a timeout is not in retryOn
            assertThat(Thread.currentThread().isInterrupted()).isFalse();
        });
    }
}
