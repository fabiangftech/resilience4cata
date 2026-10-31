package dev.resilience4cata.examples;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class FallbackEndToEndTest {

    private static final String PREFIX = "dev.resilience4cata.examples.CatalogClient#";

    private final ResilienceRegistry registry = ResilienceRegistry.builder().build();
    private final AtomicInteger priceCalls = new AtomicInteger();
    private volatile Exception describeFailure;
    private volatile RuntimeException priceFailure;

    private final CatalogClient client = Resilient.of(CatalogClient.class, new CatalogClient() {
        @Override
        public long price(String sku, int quantity) throws IOException {
            priceCalls.incrementAndGet();
            if (priceFailure != null) {
                throw priceFailure;
            }
            throw new IOException("pricing service down");
        }

        @Override
        public String describe(String sku) throws IOException {
            if (describeFailure instanceof IOException io) {
                throw io;
            }
            if (describeFailure instanceof RuntimeException runtime) {
                throw runtime;
            }
            return "product " + sku;
        }

        @Override
        public int stock(String sku) {
            return 7;
        }

        @Override
        public void refresh(String sku) throws IOException {
            throw new IllegalStateException("refresh failed");
        }
    }, registry);

    @Test
    void aHandlerGetsTheFailureTheAttemptsAndTheArguments() throws IOException {
        assertThat(client.price("sku-1", 3)).isEqualTo(300L);

        assertThat(priceCalls).hasValue(3);
        assertThat(CatalogClient.CachedPrice.last.attempts()).isEqualTo(3);
        assertThat(CatalogClient.CachedPrice.last.failure()).isInstanceOf(IOException.class).hasMessage("pricing service down");
        assertThat(CatalogClient.CachedPrice.last.argument(0, String.class)).isEqualTo("sku-1");
        assertThat(CatalogClient.CachedPrice.last.argumentCount()).isEqualTo(2);
        assertThat(CatalogClient.CachedPrice.last.id()).isEqualTo(PREFIX + "price(java.lang.String,int)");
    }

    @Test
    void skipOnFailuresAreRethrownUnchanged() {
        IllegalArgumentException invalid = new IllegalArgumentException("bad sku");
        priceFailure = invalid;
        assertThatThrownBy(() -> client.price("??", 1)).isSameAs(invalid);
    }

    @Test
    void theMostSpecificSiblingMethodWins() throws IOException {
        assertThat(client.describe("a")).isEqualTo("product a");

        describeFailure = new FileNotFoundException("a.json");
        assertThat(client.describe("a")).isEqualTo("unknown product a");

        describeFailure = new IOException("timeout");
        assertThat(client.describe("a")).isEqualTo("description unavailable: timeout");
    }

    @Test
    void aFailureNoSiblingMethodAcceptsIsRethrown() {
        IllegalStateException unexpected = new IllegalStateException("bug");
        describeFailure = unexpected;
        assertThatThrownBy(() -> client.describe("a")).isSameAs(unexpected);
    }

    @Test
    void rejectionsByAnInnerPolicyGetTheFallback() {
        assertThat(client.stock("a")).isEqualTo(7);
        registry.circuitBreaker(PREFIX + "stock(java.lang.String)").forceOpen();
        assertThat(client.stock("a")).isEqualTo(-1);
    }

    @Test
    void aCheckedExceptionFromTheHandlerIsThrownWhenTheMethodDeclaresIt() {
        assertThatThrownBy(() -> client.refresh("a"))
                .isInstanceOf(IOException.class)
                .hasMessage("fallback failed too")
                .cause().isInstanceOf(IllegalStateException.class).hasMessage("refresh failed");
    }
}
