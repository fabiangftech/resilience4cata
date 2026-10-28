package dev.resilience4cata.spring.reactive;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.CircuitBreakerState;
import dev.resilience4cata.ResilienceRegistry;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Disposable;

/**
 * A Spring WebFlux application on Netty whose beans return {@code Mono}: an annotated class (Spring creates
 * its generated subclass) and an annotated interface (its implementation bean is wrapped), called through real
 * HTTP requests. The Reactor context that a {@code WebFilter} writes reaches every attempt, failures map to
 * statuses through {@code @ExceptionHandler}, and a client that disconnects cancels the call through the
 * policies, so the bulkhead slot is freed.
 */
@SpringBootTest(classes = ReactiveWebTest.App.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReactiveWebTest {

    @SpringBootApplication(scanBasePackageClasses = ReactiveWebTest.class)
    static class App {
    }

    @Autowired
    QuoteService quotes;

    @Autowired
    RatesClient rates;

    @Autowired
    QuoteBackend backend;

    @Autowired
    ResilienceRegistry registry;

    @Autowired
    Environment environment;

    private WebTestClient client;

    @BeforeEach
    void reset() {
        backend.calls.set(0);
        backend.cancellations.set(0);
        backend.failures.set(0);
        backend.hang = false;
        client = WebTestClient.bindToServer().baseUrl(baseUrl()).responseTimeout(Duration.ofSeconds(10)).build();
    }

    private String baseUrl() {
        return "http://localhost:" + environment.getRequiredProperty("local.server.port");
    }

    @Test
    void theBeansAreTheGeneratedClasses() {
        assertThat(quotes).isInstanceOf(QuoteService$Resilient.class);
        assertThat(rates.getClass().getName()).endsWith("RatesClient$Resilient");
    }

    @Test
    void aRequestIsRetriedAndTheContextReachesEveryAttempt() {
        backend.failures.set(2);
        client.get().uri("/quotes/a").header("X-Request-Id", "req-1").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("a=42 for req-1");
        assertThat(backend.calls).hasValue(3);
    }

    @Test
    void whenTheRetriesGiveUpTheFailureMapsToAStatus() {
        backend.failures.set(10);
        client.get().uri("/quotes/a").exchange().expectStatus().isEqualTo(502);
        assertThat(backend.calls).hasValue(3);
    }

    @Test
    void theFallbackAnswersWhenTheRetriesGiveUp() {
        backend.failures.set(10);
        client.get().uri("/cached-quotes/b").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("b=40 (cached)");
        assertThat(backend.calls).hasValue(2);
    }

    @Test
    void aTimeoutCancelsTheBackendAndAnswers504() {
        backend.hang = true;
        client.get().uri("/slow-quotes/c").exchange().expectStatus().isEqualTo(504);
        awaitUntil(() -> backend.cancellations.get() == 1);
        assertThat(registry.bulkhead("slow-quotes").concurrentCalls()).isZero();
    }

    @Test
    void aClientThatDisconnectsCancelsTheCallAndFreesTheBulkhead() {
        backend.hang = true;
        Disposable request = WebClient.create(baseUrl()).get().uri("/slow-quotes/d").retrieve()
                .bodyToMono(String.class).subscribe(v -> { }, e -> { });
        awaitUntil(() -> registry.bulkhead("slow-quotes").concurrentCalls() == 1);
        client.get().uri("/slow-quotes/e").exchange().expectStatus().isEqualTo(503);

        request.dispose();
        awaitUntil(() -> registry.bulkhead("slow-quotes").concurrentCalls() == 0);
        assertThat(backend.cancellations).as("the server cancelled the backend's Mono").hasValue(1);
    }

    @Test
    void theInterfaceWrapperOpensItsBreaker() {
        backend.failures.set(10);
        client.get().uri("/rates/eur").exchange().expectStatus().isEqualTo(502);
        client.get().uri("/rates/eur").exchange().expectStatus().isEqualTo(502);
        assertThat(registry.circuitBreaker("rates").state()).isEqualTo(CircuitBreakerState.OPEN);

        client.get().uri("/rates/eur").exchange().expectStatus().isEqualTo(503);
        assertThat(backend.calls).as("rejected without calling the backend").hasValue(2);
        registry.circuitBreaker("rates").reset();
    }

    private static void awaitUntil(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime() - deadline).as("waited too long").isNegative();
            Thread.onSpinWait();
        }
    }
}
