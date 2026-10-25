package dev.resilience4cata.examples.docs.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import dev.resilience4cata.CircuitBreakerHandle;
import dev.resilience4cata.CircuitBreakerState;
import dev.resilience4cata.Pipeline;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.examples.docs.pipeline.PaymentGateway.Receipt;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** The claims of docs/{en,es}/guides/pipeline.md. */
class PipelineGuideTest {

    @Test
    void aPipelineProtectsCodeThatCannotBeAnnotated() throws IOException {
        ResilienceRegistry registry = ResilienceRegistry.builder().build();
        LegacyGeocoder legacyGeocoder = new LegacyGeocoder();
        // tag::pipeline-minimal[]
        Pipeline<String> geocode = Pipeline.builder(registry, "geocoder", String.class)
                .retry(r -> r.maxAttempts(3).delay(Duration.ofMillis(50)))
                .timeout(t -> t.timeout(Duration.ofSeconds(1)))
                .fallback(failure -> "unknown")
                .build();

        String city = geocode.execute(() -> legacyGeocoder.city("10115"));   // "Berlin", after one retry
        // end::pipeline-minimal[]

        assertThat(city).isEqualTo("Berlin");
        assertThat(geocode.execute(() -> {
            throw new IOException("down");
        })).isEqualTo("unknown");
    }

    @Test
    void everyPolicyAndBothCallStyles() throws Exception {
        PaymentGateway client = new PaymentGateway();
        String order = "o-1";
        // tag::pipeline-full[]
        ResilienceRegistry registry = ResilienceRegistry.builder()
                .retry("external", r -> r.maxAttempts(4))
                .rateLimiter("external", rl -> rl.permits(100))
                .build();

        Pipeline<Receipt> charge = Pipeline.builder(registry, "payments", Receipt.class)
                .retry("external")                                   // named config
                .circuitBreaker(cb -> cb.failureRateThreshold(25))   // defaults + overrides
                .rateLimiter("external", rl -> rl.permits(50))       // named config + overrides
                .timeout(t -> t.timeout(Duration.ofSeconds(2)))
                .bulkhead()                                          // defaults
                .fallbackHandler(context -> Receipt.pending(context.attempts()))
                .build();

        Receipt receipt = charge.execute(() -> client.charge(order));            // throws what charge throws
        CompletableFuture<Receipt> later = charge.executeAsync(() -> client.chargeAsync(order));
        charge.run(() -> client.cancel(order));                                  // a call with no result
        // end::pipeline-full[]

        assertThat(receipt.status()).isEqualTo("charged");
        assertThat(later.get(5, TimeUnit.SECONDS).status()).isEqualTo("charged");
        assertThat(registry.rateLimiter("payments").config().permits()).isEqualTo(50);
        assertThat(registry.bulkhead("payments").config().maxConcurrent()).isEqualTo(25);
        assertThat(charge.execute(() -> {
            throw new IOException("declined");
        }).status()).isEqualTo("pending after 4 attempts");
    }

    @Test
    void theIdNamesTheInstances() throws IOException {
        ResilienceRegistry registry = ResilienceRegistry.builder().build();
        // tag::pipeline-handle[]
        Pipeline<String> search = Pipeline.builder(registry, "search", String.class)
                .circuitBreaker(cb -> cb.windowSize(2).minimumCalls(2))
                .build();

        CircuitBreakerHandle breaker = registry.circuitBreaker("search");   // the pipeline's id is the instance id
        breaker.forceOpen();                                                // every call is now rejected
        // end::pipeline-handle[]

        assertThat(breaker.state()).isEqualTo(CircuitBreakerState.FORCED_OPEN);
        assertThat(search.id()).isEqualTo("search");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> search.execute(() -> "x"))
                .isInstanceOf(dev.resilience4cata.CallNotPermittedException.class);
    }

    @Test
    void theSameIdWithAnotherConfigurationIsAnError() {
        ResilienceRegistry registry = ResilienceRegistry.builder().build();
        Pipeline<String> first = Pipeline.builder(registry, "shared", String.class).retry(r -> r.maxAttempts(2)).build();
        Pipeline<String> same = Pipeline.builder(registry, "shared", String.class).retry(r -> r.maxAttempts(2)).build();
        assertThat(first).isNotSameAs(same);

        assertThatIllegalStateException()
                .isThrownBy(() -> Pipeline.builder(registry, "shared", String.class).retry(r -> r.maxAttempts(3)).build())
                .withMessageContaining("[R4C-REG-008]");
    }

    @Test
    void theBuilderOrderDoesNotChangeTheNesting() throws IOException {
        ResilienceRegistry registry = ResilienceRegistry.builder().build();
        // timeout declared before retry: the retry still wraps the timeout, so each attempt gets its own 1s
        Pipeline<String> pipeline = Pipeline.builder(registry, "ordered", String.class)
                .timeout(t -> t.timeout(Duration.ofSeconds(1)))
                .retry(r -> r.maxAttempts(2).delay(Duration.ZERO))
                .circuitBreaker()
                .build();
        LegacyGeocoder geocoder = new LegacyGeocoder();
        assertThat(pipeline.execute(() -> geocoder.city("x"))).isEqualTo("Berlin");
        assertThat(registry.circuitBreaker("ordered").snapshot().bufferedCalls())
                .as("the breaker, inside the retry, saw both attempts").isEqualTo(2);
    }

    @Test
    void annotationsAndExternalConfigurationReachPipelineInstances() throws IOException {
        java.util.Properties props = new java.util.Properties();
        props.setProperty("instances.search.circuitBreaker.minimumCalls", "7");
        ResilienceRegistry registry = dev.resilience4cata.config.ResilienceConfig.builder().properties(props).build()
                .toRegistry();
        Pipeline<String> search = Pipeline.builder(registry, "search", String.class).circuitBreaker().build();
        SearchApi api = dev.resilience4cata.Resilient.of(SearchApi.class, q -> "found", registry);

        search.execute(() -> "x");
        api.find("y");
        assertThat(registry.circuitBreakers()).hasSize(1);
        assertThat(registry.circuitBreaker("search").config().minimumCalls()).isEqualTo(7);
        assertThat(registry.circuitBreaker("search").snapshot().bufferedCalls()).isEqualTo(2);
    }
}
