package dev.resilience4cata.examples.docs.bundles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import dev.resilience4cata.RetrySnapshot;
import dev.resilience4cata.TimeoutSnapshot;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** The claims of docs/{en,es}/guides/bundles.md. */
class BundlesTest {

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

    // java:S1488: the local variable is part of a documentation snippet, which shows a registry being declared
    @SuppressWarnings("java:S1488")
    static ResilienceRegistry registry() {
        // tag::bundles-registry[]
        ResilienceRegistry registry = ResilienceRegistry.builder()
                .retryDefaults(r -> r.delay(Duration.ofMillis(100)))
                .retry("external", r -> r.maxAttempts(5))
                .circuitBreaker("external", cb -> cb.failureRateThreshold(25).waitInOpen(Duration.ofSeconds(30)))
                .build();
        // end::bundles-registry[]
        return registry;
    }

    @Test
    void theBundleAppliesToEveryMethodAndAMethodOverridesOneAttribute() throws IOException {
        ResilienceRegistry registry = registry();
        HttpShippingClient http = new HttpShippingClient();
        ShippingClient shipping = Resilient.of(ShippingClient.class, http, registry);

        assertThat(shipping.track("p-1")).isEqualTo("in transit");
        assertThat(http.trackCalls).hasValue(3);
        assertThatThrownBy(() -> shipping.cancel("p-1")).isInstanceOf(IOException.class);
        assertThat(http.cancelCalls).hasValue(1);

        String prefix = "dev.resilience4cata.examples.docs.bundles.ShippingClient#";
        assertThat(registry.snapshot().timeouts()).extracting(TimeoutSnapshot::id, t -> t.config().timeout())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(prefix + "cancel(java.lang.String)", Duration.ofSeconds(2)),
                        org.assertj.core.groups.Tuple.tuple(prefix + "track(java.lang.String)", Duration.ofSeconds(2)));
        assertThat(registry.circuitBreaker(prefix + "track(java.lang.String)").config().failureRateThreshold())
                .isEqualTo(25f);
    }

    @Test
    void bundlesNestAndTheirOwnAnnotationsWin() {
        ResilienceRegistry registry = ResilienceRegistry.builder()
                .retry("external", r -> r.maxAttempts(9).delay(Duration.ofMillis(7)))
                .circuitBreaker("external", cb -> cb.failureRateThreshold(25))
                .build();
        Resilient.of(CatalogClient.class, id -> "p", registry);

        RetrySnapshot retry = registry.snapshot().retries().get(0);
        assertThat(retry.config().maxAttempts()).as("Idempotent's own @Retry").isEqualTo(5);
        assertThat(retry.config().delay()).as("config = external, from RemoteCall").isEqualTo(Duration.ofMillis(7));
        assertThat(registry.snapshot().timeouts()).extracting(t -> t.config().timeout())
                .containsExactly(Duration.ofSeconds(2));
        assertThat(registry.snapshot().circuitBreakers()).extracting(c -> c.id())
                .containsExactly("dev.resilience4cata.examples.docs.bundles.CatalogClient#product(java.lang.String)");
    }

    @Test
    void aMethodLevelBundleBeatsATypeLevelAnnotation() {
        ResilienceRegistry registry = ResilienceRegistry.builder()
                .retry("external", r -> r.maxAttempts(9).delay(Duration.ofMillis(1)))
                .circuitBreaker("external", cb -> { })
                .build();
        Resilient.of(LegacyCatalogClient.class, new LegacyCatalogClient() {
            @Override
            public String product(String id) {
                return "p";
            }

            @Override
            public String category(String id) {
                return "c";
            }
        }, registry);

        String prefix = "dev.resilience4cata.examples.docs.bundles.LegacyCatalogClient#";
        assertThat(registry.snapshot().retries()).extracting(RetrySnapshot::id, r -> r.config().maxAttempts())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(prefix + "category(java.lang.String)", 2),
                        org.assertj.core.groups.Tuple.tuple(prefix + "product(java.lang.String)", 5));
    }

    @Test
    void theWrappedInterfaceBeatsTheDeclaringSuperinterface() {
        ResilienceRegistry registry = ResilienceRegistry.builder().build();
        Resilient.of(ChildApi.class, id -> "x", registry);

        RetrySnapshot retry = registry.snapshot().retries().get(0);
        assertThat(retry.id()).isEqualTo("dev.resilience4cata.examples.docs.bundles.ChildApi#get(java.lang.String)");
        assertThat(retry.config().maxAttempts()).isEqualTo(3);
        assertThat(retry.config().delay()).isEqualTo(Duration.ofMillis(1));
    }
}
