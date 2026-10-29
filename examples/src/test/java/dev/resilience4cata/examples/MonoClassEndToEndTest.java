package dev.resilience4cata.examples;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.CallTimeoutException;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import dev.resilience4cata.testkit.ManualClock;
import dev.resilience4cata.testkit.ResilienceTesting;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/** An annotated class whose methods return {@code Mono}: the generated subclass, calls on {@code this} included. */
class MonoClassEndToEndTest {

    private static final Duration VERIFY = Duration.ofSeconds(5);

    private final ManualClock clock = new ManualClock();
    private final ResilienceRegistry registry = ResilienceTesting.registry(clock).build();
    private final MonoService service = Resilient.create(MonoService.class, registry, "hello");

    @Test
    void theSubclassRetriesThroughSuper() {
        assertThat(service).isInstanceOf(MonoService$Resilient.class);
        service.failures = 2;
        StepVerifier.create(service.greet("ana"))
                .then(() -> clock.advance(Duration.ofMillis(100))).then(() -> clock.advance(Duration.ofMillis(100)))
                .expectNext("hello ana").expectComplete().verify(VERIFY);
        assertThat(service.calls).hasValue(3);
    }

    @Test
    void theFallbackIsCalledOnThis() {
        service.failures = 5;
        StepVerifier.create(service.greet("ana"))
                .then(() -> clock.advance(Duration.ofMillis(100))).then(() -> clock.advance(Duration.ofMillis(100)))
                .expectNext("unavailable for ana").expectComplete().verify(VERIFY);
    }

    @Test
    void callsOnThisGoThroughThePolicies() {
        service.failures = 1;
        StepVerifier.create(service.greetTwice("bo"))
                .then(() -> clock.advance(Duration.ofMillis(100)))
                .expectNext("hello bo / hello bo").expectComplete().verify(VERIFY);
        assertThat(service.calls).as("the failed first call was retried").hasValue(3);
    }

    @Test
    void aTimeoutOnAnAnnotatedClass() {
        StepVerifier.create(service.slow("x")).then(() -> clock.advance(Duration.ofSeconds(1)))
                .expectError(CallTimeoutException.class).verify(VERIFY);
    }
}
