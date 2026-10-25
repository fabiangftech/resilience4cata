package dev.resilience4cata.examples;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.resilience4cata.CircuitBreakerSnapshot;
import dev.resilience4cata.CircuitBreakerState;
import dev.resilience4cata.Pipeline;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.ResilienceSnapshot;
import dev.resilience4cata.Resilient;
import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.Fallback;
import dev.resilience4cata.annotation.ResiliencePolicy;
import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.annotation.Timeout;
import dev.resilience4cata.config.ResilienceConfig;
import dev.resilience4cata.event.CircuitBreakerEvent;
import dev.resilience4cata.event.RetryEvent;
import dev.resilience4cata.testkit.ManualClock;
import dev.resilience4cata.testkit.ResilienceTesting;
import java.io.IOException;
import java.lang.System.Logger.Level;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The code of {@code docs/en/getting-started.md} (and {@code docs/es/getting-started.md}), compiled and
 * run. Keep both in sync by hand: every snippet on the page appears here unchanged, marked with the
 * section it belongs to. The resource {@code resilience4cata.properties} is the page's properties file.
 */
class GuideTest {

    // ---- "Your first annotated interface" ----

    public interface PriceClient {

        @Retry(maxAttempts = 3, delay = "100ms")
        @CircuitBreaker(name = "prices")
        @Timeout("2s")
        @Fallback(method = "unknownPrice")
        BigDecimal price(String sku) throws IOException;

        default BigDecimal unknownPrice(String sku, Exception failure) {
            return BigDecimal.ZERO;
        }
    }

    /** The implementation to protect: its first call fails, the next ones return 9.99. */
    static final class HttpPriceClient implements PriceClient {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public BigDecimal price(String sku) throws IOException {
            if (calls.incrementAndGet() == 1) {
                throw new IOException("connection reset");
            }
            return new BigDecimal("9.99");
        }
    }

    // ---- "An annotated class": StockService.java and Warehouse.java ----

    // ---- "The programmatic API" ----

    /** A third-party class that cannot be annotated: fails once, then answers. */
    static final class LegacyGeocoder {
        private final AtomicInteger calls = new AtomicInteger();

        String city(String zip) throws IOException {
            if (calls.incrementAndGet() == 1) {
                throw new IOException("timeout");
            }
            return "Berlin";
        }
    }

    // ---- "Named configurations and composed annotations" ----

    @ResiliencePolicy
    @Retention(RetentionPolicy.CLASS)
    @Target({ElementType.METHOD, ElementType.TYPE})
    @Retry(config = "external")
    @CircuitBreaker(config = "external")
    @Timeout("2s")
    public @interface RemoteCall {
    }

    @RemoteCall
    public interface ShippingClient {

        String track(String parcel) throws IOException;

        @Retry(maxAttempts = 1)   // overrides one attribute of the bundle: never retried
        String cancel(String parcel) throws IOException;
    }

    /** Fails the first two calls of each method. */
    static final class HttpShippingClient implements ShippingClient {
        final AtomicInteger trackCalls = new AtomicInteger();
        final AtomicInteger cancelCalls = new AtomicInteger();

        @Override
        public String track(String parcel) throws IOException {
            if (trackCalls.incrementAndGet() <= 2) {
                throw new IOException("busy");
            }
            return "in transit";
        }

        @Override
        public String cancel(String parcel) throws IOException {
            if (cancelCalls.incrementAndGet() <= 2) {
                throw new IOException("busy");
            }
            return "cancelled";
        }
    }

    // ---- "Events and snapshots" ----

    private static final System.Logger LOG = System.getLogger("shop.resilience");

    /** Not annotated, so no generated class: {@code Resilient.of} fails with R4C-RT-003. */
    public interface NotProcessed {
        String call();
    }

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @Test
    void firstAnnotatedInterface() throws IOException {
        ResilienceRegistry registry = ResilienceRegistry.builder().build();
        PriceClient prices = Resilient.of(PriceClient.class, new HttpPriceClient(), registry);

        BigDecimal price = prices.price("sku-1");   // retried, timed out after 2 s, guarded by the "prices" breaker

        assertThat(price).isEqualByComparingTo("9.99");
        assertThat(registry.circuitBreaker("prices").state()).isEqualTo(CircuitBreakerState.CLOSED);
    }

    @Test
    void fallbackRunsWhenEveryAttemptFails() throws IOException {
        ResilienceRegistry registry = ResilienceRegistry.builder().build();
        PriceClient prices = Resilient.of(PriceClient.class, sku -> {
            throw new IOException("down");
        }, registry);

        assertThat(prices.price("sku-1")).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void annotatedClass() {
        ResilienceRegistry registry = ResilienceRegistry.builder().build();
        AtomicInteger counts = new AtomicInteger();
        Warehouse warehouse = sku -> {
            if (counts.incrementAndGet() % 2 == 1) {
                throw new IllegalStateException("warehouse busy");   // every first try fails
            }
            return 5;
        };

        StockService stock = Resilient.create(StockService.class, registry, warehouse);
        int total = stock.total(List.of("a", "b"));   // each stock(sku) call is retried

        assertThat(total).isEqualTo(10);
        assertThat(counts).hasValue(4);
    }

    @Test
    void programmaticPipeline() throws IOException {
        ResilienceRegistry registry = ResilienceRegistry.builder().build();
        LegacyGeocoder legacyGeocoder = new LegacyGeocoder();

        Pipeline<String> geocode = Pipeline.builder(registry, "geocoder", String.class)
                .retry(r -> r.maxAttempts(3).delay(Duration.ofMillis(50)))
                .timeout(t -> t.timeout(Duration.ofSeconds(1)))
                .fallback(failure -> "unknown")
                .build();

        String city = geocode.execute(() -> legacyGeocoder.city("10115"));

        assertThat(city).isEqualTo("Berlin");
    }

    @Test
    void namedConfigurations() throws IOException {
        ResilienceRegistry registry = ResilienceRegistry.builder()
                .retryDefaults(r -> r.delay(Duration.ofMillis(100)))
                .retry("external", r -> r.maxAttempts(5))
                .circuitBreaker("external", cb -> cb.failureRateThreshold(25).waitInOpen(Duration.ofSeconds(30)))
                .build();
        HttpShippingClient http = new HttpShippingClient();
        ShippingClient shipping = Resilient.of(ShippingClient.class, http, registry);

        assertThat(shipping.track("p-1")).isEqualTo("in transit");
        assertThat(http.trackCalls).hasValue(3);
        assertThatThrownBy(() -> shipping.cancel("p-1")).isInstanceOf(IOException.class);
        assertThat(http.cancelCalls).hasValue(1);
    }

    @Test
    void externalConfiguration() {
        ResilienceConfig config = ResilienceConfig.load();   // resilience4cata.properties, R4C_* variables, r4c.* properties
        ResilienceRegistry registry = config
                .applyTo(ResilienceRegistry.builder()
                        .retry("external", r -> r.maxAttempts(5)))   // the file's maxAttempts=4 wins
                .build();
        ShippingClient shipping = Resilient.of(ShippingClient.class, new HttpShippingClient(), registry);
        PriceClient prices = Resilient.of(PriceClient.class, new HttpPriceClient(), registry);
        config.verifyInstances(registry);   // fails if an instances.<id> key matches no instance

        assertThat(shipping).isNotNull();
        assertThat(prices).isNotNull();
        assertThat(registry.retryConfig("external").maxAttempts()).isEqualTo(4);
        assertThat(registry.circuitBreakerConfig("external").waitInOpen()).isEqualTo(Duration.ofSeconds(10));
        assertThat(registry.circuitBreaker("prices").config().failureRateThreshold()).isEqualTo(30f);
    }

    @Test
    void eventsAndSnapshots() throws IOException {
        LongAdder retries = new LongAdder();
        ResilienceRegistry registry = ResilienceRegistry.builder()
                .listen(CircuitBreakerEvent.StateTransition.class,
                        t -> LOG.log(Level.WARNING, "{0}: {1} -> {2}", t.instance(), t.from(), t.to()))
                .listen(RetryEvent.Retrying.class, e -> retries.increment())
                .build();
        PriceClient prices = Resilient.of(PriceClient.class, new HttpPriceClient(), registry);
        prices.price("sku-1");   // the first attempt fails, the second succeeds

        CircuitBreakerSnapshot breaker = registry.circuitBreaker("prices").snapshot();
        ResilienceSnapshot all = registry.snapshot();   // every instance created so far

        assertThat(retries.sum()).isEqualTo(1);
        assertThat(breaker.state()).isEqualTo(CircuitBreakerState.CLOSED);
        assertThat(breaker.bufferedCalls()).isEqualTo(2);
        assertThat(breaker.failedCalls()).isEqualTo(1);
        assertThat(all.circuitBreakers()).extracting(CircuitBreakerSnapshot::instance).containsExactly("prices");
    }

    @Test
    void testingWithAManualClock() throws IOException {
        long started = System.nanoTime();

        ManualClock clock = new ManualClock();
        ResilienceRegistry registry = ResilienceTesting.registry(clock).build();   // also seeds the jitter
        PriceClient prices = Resilient.of(PriceClient.class, new HttpPriceClient(), registry);

        assertThat(prices.price("sku-1")).isEqualByComparingTo("9.99");   // one retry, no real sleep
        assertThat(clock.elapsed()).isBetween(Duration.ZERO, Duration.ofMillis(100));

        assertThat(System.nanoTime() - started).isLessThan(Duration.ofSeconds(1).toNanos());
    }

    @Test
    void missingProcessorFailsFast() {
        ResilienceRegistry registry = ResilienceRegistry.builder().build();

        assertThatIllegalStateException()
                .isThrownBy(() -> Resilient.of(NotProcessed.class, () -> "x", registry))
                .withMessageStartingWith("[R4C-RT-003] No generated class " + NotProcessed.class.getName() + "$Resilient found.")
                .withMessageContaining("annotationProcessor 'dev.resilience4cata:resilience4cata-processor:<version>'");
    }
}
