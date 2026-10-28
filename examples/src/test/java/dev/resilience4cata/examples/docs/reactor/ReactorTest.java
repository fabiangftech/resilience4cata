package dev.resilience4cata.examples.docs.reactor;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.CallTimeoutException;
import dev.resilience4cata.Pipeline;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import dev.resilience4cata.reactor.PipelineOperator;
import dev.resilience4cata.testkit.ManualClock;
import dev.resilience4cata.testkit.ResilienceTesting;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

/** The code of docs/en/guides/reactor.md. */
class ReactorTest {

    private final AtomicInteger calls = new AtomicInteger();

    /** Fails twice, then answers 42; reads the tenant from the Reactor context. */
    private final PriceClient http = sku -> Mono.deferContextual(context -> calls.incrementAndGet() <= 2
            ? Mono.error(new IOException("prices unavailable"))
            : Mono.just(new BigDecimal(42)));

    @Test
    void aMonoMethodRetriesPerSubscription() {
        // tag::reactor-call[]
        ResilienceRegistry registry = ResilienceRegistry.builder().build();
        PriceClient prices = Resilient.of(PriceClient.class, http, registry);

        Mono<BigDecimal> price = prices.price("sku-1");   // nothing runs yet
        BigDecimal value = price.block();                 // subscribes: two failed attempts, then 42
        // end::reactor-call[]
        assertThat(value).isEqualByComparingTo("42");
        assertThat(calls).hasValue(3);

        price.block();
        assertThat(calls).as("every subscription is a new call").hasValue(4);
    }

    @Test
    void theContextReachesTheDelegate() {
        PriceClient tenantAware = sku -> Mono.deferContextual(context ->
                Mono.just(new BigDecimal(context.<String>get("tenant").length())));
        PriceClient prices = Resilient.of(PriceClient.class, tenantAware, ResilienceRegistry.builder().build());
        // tag::reactor-context[]
        Mono<BigDecimal> price = prices.price("sku-1")
                .contextWrite(Context.of("tenant", "acme"));   // the delegate's Mono, and every retry, see it
        // end::reactor-context[]
        assertThat(price.block()).isEqualByComparingTo("4");
    }

    @Test
    void aPipelineAsAnOperator() {
        ResilienceRegistry registry = ResilienceRegistry.builder().build();
        Mono<String> webClientCall = Mono.defer(() -> calls.incrementAndGet() == 1
                ? Mono.error(new IOException("first")) : Mono.just("rates"));
        // tag::reactor-operator[]
        PipelineOperator<String> resilient = PipelineOperator.of(Pipeline.builder(registry, "rates", String.class)
                .retry(r -> r.maxAttempts(3).delay(Duration.ofMillis(50)))
                .timeout(t -> t.timeout(Duration.ofSeconds(2)))
                .build());

        Mono<String> rates = webClientCall.transform(resilient);   // each attempt subscribes to webClientCall again
        // end::reactor-operator[]
        assertThat(rates.block()).isEqualTo("rates");
        assertThat(calls).hasValue(2);
    }

    @Test
    void testingOnAManualClock() {
        PriceClient slow = sku -> Mono.never();
        // tag::reactor-testing[]
        ManualClock clock = new ManualClock();
        ResilienceRegistry registry = ResilienceTesting.registry(clock).build();
        PriceClient prices = Resilient.of(PriceClient.class, slow, registry);

        StepVerifier.create(prices.price("sku-1"))
                .then(() -> clock.advance(Duration.ofSeconds(7)))   // three 2 s timeouts and the delays between them
                .expectNext(BigDecimal.ZERO)                        // the fallback
                .verifyComplete();
        // end::reactor-testing[]
        assertThat(clock.pendingTasks()).isZero();
    }

    @Test
    void aTimeoutWithoutFallbackFailsTheMono() {
        ManualClock clock = new ManualClock();
        ResilienceRegistry registry = ResilienceTesting.registry(clock).build();
        PipelineOperator<String> timed = PipelineOperator.of(Pipeline.builder(registry, "timed", String.class)
                .timeout(t -> t.timeout(Duration.ofSeconds(1))).build());
        StepVerifier.create(Mono.<String>never().transform(timed))
                .then(() -> clock.advance(Duration.ofSeconds(1)))
                .expectError(CallTimeoutException.class)
                .verify(Duration.ofSeconds(5));
    }
}
