package dev.resilience4cata.nativeimage.plainjava;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.resilience4cata.CallNotPermittedException;
import dev.resilience4cata.CallTimeoutException;
import dev.resilience4cata.BulkheadFullException;
import dev.resilience4cata.Pipeline;
import dev.resilience4cata.RateLimitExceededException;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import dev.resilience4cata.config.ConfigException;
import dev.resilience4cata.config.ResilienceConfig;
import dev.resilience4cata.event.RetryEvent;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * resilience4cata in a plain Java application, with no framework to register anything for the native image: on the
 * JVM ({@code test}) and inside a GraalVM native image ({@code nativeTest}). What makes the image work is only what
 * the library ships: the processor's reachability metadata for each generated class ({@code Resilient.of} and
 * {@code Resilient.create} find them by name), core's run-time initialization of its thread-starting holders, and
 * the config module's metadata for {@code resilience4cata.properties} and the exception classes it names.
 */
class PlainJavaNativeTest {

    private final List<RetryEvent.GaveUp> gaveUp = new CopyOnWriteArrayList<>();
    private ResilienceRegistry registry;
    private Warehouse warehouse;
    private Inventory inventory;

    @BeforeEach
    void wrap() {
        registry = ResilienceConfig.load()
                .applyTo(ResilienceRegistry.builder().listen(RetryEvent.GaveUp.class, gaveUp::add))
                .build();
        warehouse = new Warehouse();
        inventory = Resilient.of(Inventory.class, warehouse, registry);
    }

    @Test
    void theWrapperIsTheGeneratedClass() {
        assertThat(inventory.getClass().getName()).isEqualTo("dev.resilience4cata.nativeimage.plainjava.Inventory$Resilient");
    }

    @Test
    void aWrappedInterfaceRetriesThenFallsBack() {
        warehouse.failures.set(2);
        assertThat(inventory.stock("abc")).isEqualTo(3);
        assertThat(warehouse.calls).hasValue(3);

        warehouse.failures.set(5);
        assertThat(inventory.stock("abc")).isEqualTo(-1);
        assertThat(gaveUp).hasSize(1);
    }

    @Test
    void theClasspathConfigurationAppliesWithTheExceptionClassItNames() {
        // The annotation alone would give up after 3 attempts; the file allows 4 and retries OutOfStockException.
        warehouse.failures.set(3);
        assertThat(inventory.reserve("abcd")).isEqualTo(4);
        assertThat(warehouse.calls).hasValue(4);
        assertThat(registry.retryConfig("inventory").retryOn()).containsExactly(OutOfStockException.class);
        assertThat(registry.retryConfig("inventory").abortOn()).containsExactly(IllegalArgumentException.class);
    }

    /**
     * A class the image cannot find by name (here one that does not exist, as an application class nobody registered
     * looks from inside the image): in a native image the error says how to register it.
     */
    @Test
    void anUnregisteredClassIsReportedWithWhatToRegister() {
        String name = "dev.resilience4cata.nativeimage.plainjava.NeverRegisteredException";
        assertThatThrownBy(() -> ResilienceConfig.builder()
                .properties("test", Map.of("r4c.defaults.retry.retryOn", name), key -> null).build())
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("R4C-CFG-003")
                .satisfies(e -> {
                    if ("runtime".equals(System.getProperty("org.graalvm.nativeimage.imagecode"))) {
                        assertThat(e).hasMessageContaining("{\"reflection\": [{\"type\": \"" + name + "\"}]}");
                    }
                });
    }

    @Test
    void aCircuitBreakerOpens() {
        warehouse.failures.set(4);
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> inventory.count("abc")).isInstanceOf(OutOfStockException.class);
        }
        assertThatThrownBy(() -> inventory.count("abc")).isInstanceOf(CallNotPermittedException.class);
        assertThat(warehouse.calls).hasValue(4);
        assertThat(registry.circuitBreaker("warehouse").snapshot().state().name()).isEqualTo("OPEN");
    }

    @Test
    void aSyncTimeoutInterruptsTheCall() {
        long start = System.nanoTime();
        assertThatThrownBy(() -> inventory.slow(10_000)).isInstanceOf(CallTimeoutException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void anAsyncTimeoutFailsTheStage() {
        CompletableFuture<String> stage = inventory.later(new CompletableFuture<>());
        assertThatThrownBy(() -> stage.get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class).cause().isInstanceOf(CallTimeoutException.class);
    }

    @Test
    void aHedgeAnswersWhenTheFirstAttemptDoesNot() throws Exception {
        assertThat(inventory.hedged("abc").get(5, TimeUnit.SECONDS)).isEqualTo("abc");
        assertThat(warehouse.calls).hasValue(2);
    }

    @Test
    void aFullBulkheadRejects() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread first = Thread.ofVirtual().start(() -> {
            try {
                inventory.alone(inside, release);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> inventory.alone(new CountDownLatch(1), new CountDownLatch(0)))
                .isInstanceOf(BulkheadFullException.class);
        release.countDown();
        first.join(5_000);
        assertThat(inventory.alone(new CountDownLatch(1), new CountDownLatch(0))).isEqualTo("alone");
    }

    @Test
    void aRateLimiterRejectsPastItsPermits() {
        assertThat(inventory.limited()).isEqualTo("ok");
        assertThat(inventory.limited()).isEqualTo("ok");
        assertThatThrownBy(inventory::limited).isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void anAnnotatedClassThroughItsVarargsConstructor() {
        Prices prices = Resilient.create(Prices.class, registry, "EUR", "eu", "us");
        assertThat(prices.getClass().getName()).isEqualTo("dev.resilience4cata.nativeimage.plainjava.Prices$Resilient");

        prices.failNext(2);
        assertThat(prices.price("abc")).isEqualTo("abc: 30 EUR in [eu, us]");
        assertThat(prices.calls).hasValue(3);

        prices.failNext(2); // self-invocation: the first inner call retries through the override
        assertThat(prices.twice("x")).isEqualTo("x: 30 EUR in [eu, us] / x: 30 EUR in [eu, us]");
    }

    @Test
    void monoAndFluxMethodsRetryAndFallBack() {
        Quotes quotes = Resilient.create(Quotes.class, registry);
        assertThat(quotes.rate("EUR").block(Duration.ofSeconds(5))).isEqualTo("EUR=1.1");
        assertThat(quotes.subscriptions).hasValue(3);
        assertThat(quotes.ticks("ACME").collectList().block(Duration.ofSeconds(5))).containsExactly("ACME: closed");
    }

    @Test
    void aPipelineProtectsALambda() {
        AtomicInteger calls = new AtomicInteger();
        Pipeline<String> pipeline = Pipeline.builder(registry, "lambda", String.class)
                .retry(r -> r.maxAttempts(2).delay(Duration.ofMillis(1)))
                .timeout(t -> t.timeout(Duration.ofSeconds(5)))
                .build();
        String result = pipeline.execute(() -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("first call fails");
            }
            return "second";
        });
        assertThat(result).isEqualTo("second");
    }

    @Test
    void theSnapshotListsTheInstances() {
        inventory.stock("abc");
        Map<String, Object> map = registry.snapshot().toMap();
        assertThat(map).containsKey("retries");
        assertThat(map.get("retries").toString()).contains("Inventory#stock(java.lang.String)");
    }

    /** No generated class: the processor's absence is reported with its code, as on the JVM. */
    @Test
    void anInterfaceWithoutAGeneratedClassFailsLoudly() {
        assertThatThrownBy(() -> Resilient.of(Runnable.class, () -> { }, registry))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("R4C-RT-");
    }
}
