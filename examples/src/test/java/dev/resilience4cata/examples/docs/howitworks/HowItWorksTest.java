package dev.resilience4cata.examples.docs.howitworks;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.CircuitBreakerSnapshot;
import dev.resilience4cata.CircuitBreakerState;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import dev.resilience4cata.RetrySnapshot;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** The claims of docs/{en,es}/start/how-it-works.md. */
class HowItWorksTest {

    private static final String ID = "dev.resilience4cata.examples.docs.howitworks.StockClient#stock(java.lang.String,int)";

    @Test
    void theGeneratedClassImplementsTheInterfaceAndDelegates() throws ReflectiveOperationException {
        Class<?> generated = Class.forName(StockClient.class.getName() + "$Resilient");
        assertThat(generated.getInterfaces()).containsExactly(StockClient.class);
        assertThat(java.lang.reflect.Modifier.isFinal(generated.getModifiers())).isTrue();
        assertThat(generated.getConstructor(StockClient.class, ResilienceRegistry.class)).isNotNull();
    }

    @Test
    void eachAttemptGoesThroughTheBreakerUnderADerivedId() {
        ResilienceRegistry registry = ResilienceRegistry.builder().build();
        // tag::how-it-works-ids[]
        AtomicInteger calls = new AtomicInteger();
        StockClient stock = Resilient.of(StockClient.class, (sku, warehouse) -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("warehouse busy");
            }
            return 7;
        }, registry);

        int count = stock.stock("sku-1", 3);   // the first attempt fails, the retry succeeds

        CircuitBreakerSnapshot breaker = registry.circuitBreaker(
                "dev.resilience4cata.examples.docs.howitworks.StockClient#stock(java.lang.String,int)").snapshot();
        // breaker.bufferedCalls() == 2 and breaker.failedCalls() == 1: the breaker saw both attempts
        // end::how-it-works-ids[]

        assertThat(count).isEqualTo(7);
        assertThat(calls).hasValue(2);
        assertThat(breaker.id()).isEqualTo(ID);
        assertThat(breaker.state()).isEqualTo(CircuitBreakerState.CLOSED);
        assertThat(breaker.bufferedCalls()).isEqualTo(2);
        assertThat(breaker.failedCalls()).isEqualTo(1);
        assertThat(registry.snapshot().retries()).extracting(RetrySnapshot::id).containsExactly(ID);
    }

    @Test
    void configurationIsResolvedOnceFromTheRegistry() {
        // tag::how-it-works-registry[]
        ResilienceRegistry registry = ResilienceRegistry.builder()
                .retryDefaults(r -> r.delay(Duration.ofMillis(50)))                     // every retry
                .circuitBreakerInstance(
                        "dev.resilience4cata.examples.docs.howitworks.StockClient#stock(java.lang.String,int)",
                        cb -> cb.minimumCalls(20))                                     // one instance
                .build();
        StockClient stock = Resilient.of(StockClient.class, (sku, warehouse) -> 7, registry);   // resolved here
        // end::how-it-works-registry[]

        assertThat(stock.stock("a", 1)).isEqualTo(7);
        RetrySnapshot retry = registry.snapshot().retries().get(0);
        assertThat(retry.config().delay()).isEqualTo(Duration.ofMillis(50));
        assertThat(retry.config().maxAttempts()).isEqualTo(3);   // from the annotation
        assertThat(registry.circuitBreaker(ID).config().minimumCalls()).isEqualTo(20);
    }

    @Test
    void sameNameMeansOneSharedInstance() {
        ResilienceRegistry registry = ResilienceRegistry.builder().build();
        StockClient first = Resilient.of(StockClient.class, (sku, warehouse) -> 1, registry);
        StockClient second = Resilient.of(StockClient.class, (sku, warehouse) -> 2, registry);
        first.stock("a", 1);
        second.stock("a", 1);

        assertThat(registry.circuitBreakers()).hasSize(1);
        assertThat(registry.circuitBreaker(ID).snapshot().bufferedCalls()).isEqualTo(2);
    }
}
