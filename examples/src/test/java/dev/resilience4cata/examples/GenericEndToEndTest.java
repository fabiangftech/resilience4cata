package dev.resilience4cata.examples;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class GenericEndToEndTest {

    private final ResilienceRegistry registry = ResilienceRegistry.builder().build();
    private final AtomicInteger loadCalls = new AtomicInteger();
    private final AtomicInteger fetchCalls = new AtomicInteger();
    private volatile RuntimeException loadFailure;

    private final GenericClient client = Resilient.of(GenericClient.class, new GenericClient() {
        @Override
        public <T> T load(Class<T> type, String id) throws IOException {
            loadCalls.incrementAndGet();
            if (loadFailure != null) {
                throw loadFailure;
            }
            if (id.equals("ok")) {
                return type.cast(type == String.class ? "loaded" : null);
            }
            throw new IOException("store down");
        }

        @Override
        public <T extends Comparable<T>> T largest(List<T> values) {
            if (values.size() > 2) {
                throw new IllegalStateException("too many");
            }
            return values.stream().max(Comparable::compareTo).orElseThrow();
        }

        @Override
        public <T> String describe(T value) {
            throw new UnsupportedOperationException("no description");
        }

        @Override
        public <T> CompletionStage<T> fetch(Class<T> type, String id) {
            fetchCalls.incrementAndGet();
            return CompletableFuture.failedFuture(new IllegalStateException("remote down"));
        }
    }, registry);

    @Test
    void aGenericMethodGetsItsGenericFallbackAfterTheRetries() throws IOException {
        assertThat(client.load(String.class, "ok")).isEqualTo("loaded");
        assertThat(loadCalls).hasValue(1);

        String loaded = client.load(String.class, "x");

        assertThat(loaded).isEqualTo("cached x");
        assertThat(loadCalls).hasValue(4);
    }

    @Test
    void failuresTheFallbackDoesNotTakeAreRethrown() {
        loadFailure = new IllegalArgumentException("bad id");

        assertThatThrownBy(() -> client.load(String.class, "x")).isInstanceOf(IllegalArgumentException.class);
        assertThat(loadCalls).hasValue(3);
    }

    @Test
    void aStaticGenericFallbackWithARecursiveBound() {
        assertThat(client.largest(List.of(3, 9))).isEqualTo(9);
        assertThat(client.largest(List.of("b", "c", "a"))).isEqualTo("a");
    }

    @Test
    void aHandlerOnAGenericMethodWhoseResultIsNotGeneric() {
        assertThat(client.describe(42)).isEqualTo("unknown 42");
    }

    @Test
    void anAsyncGenericMethodGetsItsGenericFallback() throws Exception {
        Integer value = client.fetch(Integer.class, "x").toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertThat(value).isEqualTo(-1);
        assertThat(fetchCalls).hasValue(2);
    }
}
