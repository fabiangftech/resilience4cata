package dev.resilience4cata.examples;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import dev.resilience4cata.RetryConfig;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RetryEndToEndTest {

    private final ResilienceRegistry registry = ResilienceRegistry.builder()
            .retryDefaults(r -> r.delay(Duration.ofMillis(1)).jitter(RetryConfig.Jitter.NONE))
            .retry("inventory", r -> r.maxAttempts(5))
            .retry("external", r -> r.maxAttempts(4))
            .build();

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @Test
    void retriesUntilTheCallSucceeds() {
        Flaky flaky = new Flaky(2);
        InventoryClient client = Resilient.of(InventoryClient.class, new FakeInventory(flaky), registry);

        assertThat(client.stock("sku-1")).isEqualTo(42);
        assertThat(flaky.calls()).isEqualTo(3);
    }

    @Test
    void rethrowsTheLastFailureWhenAttemptsAreExhausted() {
        Flaky flaky = new Flaky(10);
        InventoryClient client = Resilient.of(InventoryClient.class, new FakeInventory(flaky), registry);

        assertThatIllegalStateException().isThrownBy(() -> client.stock("sku-1")).withMessage("temporary failure");
        assertThat(flaky.calls()).isEqualTo(3);
    }

    @Test
    void checkedExceptionsAreRetriedAndPropagated() {
        AtomicInteger calls = new AtomicInteger();
        InventoryClient client = Resilient.of(InventoryClient.class, new FakeInventory(new Flaky(0)) {
            @Override
            public String fetch(String url) throws IOException {
                calls.incrementAndGet();
                throw new IOException("unreachable: " + url);
            }
        }, registry);

        assertThatThrownBy(() -> client.fetch("http://x")).isInstanceOf(IOException.class).hasMessage("unreachable: http://x");
        assertThat(calls).hasValue(4);
    }

    @Test
    void retryOnLimitsWhichExceptionsAreRetried() {
        AtomicInteger calls = new AtomicInteger();
        InventoryClient client = Resilient.of(InventoryClient.class, new FakeInventory(new Flaky(0)) {
            @Override
            public String fetch(String url) {
                calls.incrementAndGet();
                throw new IllegalStateException("not an IOException");
            }
        }, registry);

        assertThatIllegalStateException().isThrownBy(() -> client.fetch("http://x"));
        assertThat(calls).hasValue(1);
    }

    @Test
    void abortOnStopsRetryingImmediately() {
        Flaky flaky = new Flaky(5, () -> new IllegalArgumentException("bad quantity"));
        InventoryClient client = Resilient.of(InventoryClient.class, new FakeInventory(flaky), registry);

        assertThatIllegalArgumentException().isThrownBy(() -> client.reserve("sku-1", -1));
        assertThat(flaky.calls()).isEqualTo(1);
    }

    @Test
    void voidMethodsAreRetried() {
        Flaky flaky = new Flaky(1);
        InventoryClient client = Resilient.of(InventoryClient.class, new FakeInventory(flaky), registry);

        client.reserve("sku-1", 1);
        assertThat(flaky.calls()).isEqualTo(2);
    }

    @Test
    void disabledRetryCallsOnce() {
        Flaky flaky = new Flaky(1);
        InventoryClient client = Resilient.of(InventoryClient.class, new FakeInventory(flaky), registry);

        assertThatIllegalStateException().isThrownBy(client::disabled);
        assertThat(flaky.calls()).isEqualTo(1);
    }

    @Test
    void namedConfigComesFromTheRegistry() {
        Flaky flaky = new Flaky(4);
        InventoryClient client = Resilient.of(InventoryClient.class, new FakeInventory(flaky), registry);

        assertThat(client.named()).isEqualTo(7);
        assertThat(flaky.calls()).isEqualTo(5);
    }

    @Test
    void genericMethodsAreRetried() {
        Flaky flaky = new Flaky(1);
        InventoryClient client = Resilient.of(InventoryClient.class, new FakeInventory(flaky), registry);

        assertThat(client.max(List.of(3, 9, 4))).isEqualTo(9);
        assertThat(flaky.calls()).isEqualTo(2);
    }

    @Test
    void methodsWithoutPoliciesAndDefaultMethodsAreDelegated() {
        Flaky flaky = new Flaky(1);
        InventoryClient client = Resilient.of(InventoryClient.class, new FakeInventory(flaky) {
            @Override
            public String describe() {
                return "overridden";
            }
        }, registry);

        assertThatIllegalStateException().isThrownBy(client::unprotected);
        assertThat(flaky.calls()).isEqualTo(1);
        assertThat(client.describe()).isEqualTo("overridden");
    }

    @Test
    void composedAnnotationAppliesToEveryMethodAndCanBeOverridden() {
        AtomicInteger charges = new AtomicInteger();
        AtomicInteger refunds = new AtomicInteger();
        PaymentClient client = Resilient.of(PaymentClient.class, new PaymentClient() {
            @Override
            public String charge(String order) {
                charges.incrementAndGet();
                throw new IllegalStateException("down");
            }

            @Override
            public String refund(String order) {
                refunds.incrementAndGet();
                throw new IllegalStateException("down");
            }
        }, registry);

        assertThatIllegalStateException().isThrownBy(() -> client.charge("o-1"));
        assertThatIllegalStateException().isThrownBy(() -> client.refund("o-1"));
        assertThat(charges).hasValue(4);   // maxAttempts from the "external" config
        assertThat(refunds).hasValue(2);   // overridden on the method
    }

    @Test
    void genericAndNestedInterfacesAreSupported() {
        Flaky flaky = new Flaky(1);
        Repository<String> repository = Resilient.of(cast(Repository.class), id -> {
            flaky.call();
            return "loaded " + id;
        }, registry);
        Repository.Cache cache = Resilient.of(Repository.Cache.class, key -> {
            flaky.call();
            return "cached " + key;
        }, registry);

        assertThat(repository.load("1")).isEqualTo("loaded 1");
        assertThat(cache.get("k")).isEqualTo("cached k");
        assertThat(flaky.calls()).isEqualTo(3);
    }

    @Test
    void interruptedCallerIsNotRetried() {
        Flaky flaky = new Flaky(5);
        InventoryClient client = Resilient.of(InventoryClient.class, new FakeInventory(flaky), registry);

        Thread.currentThread().interrupt();
        assertThatIllegalStateException().isThrownBy(() -> client.stock("sku-1"));
        assertThat(flaky.calls()).isEqualTo(1);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    void unknownConfigFailsWhenWrapping() {
        ResilienceRegistry empty = ResilienceRegistry.builder().build();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> Resilient.of(InventoryClient.class, new FakeInventory(new Flaky(0)), empty))
                .withMessageContaining("Unknown retry config 'inventory'");
    }

    @Test
    void missingGeneratedClassExplainsHowToFixIt() {
        assertThatIllegalStateException()
                .isThrownBy(() -> Resilient.of(Unannotated.class, () -> 1, registry))
                .withMessageContaining("Unannotated$Resilient")
                .withMessageContaining("annotationProcessor");
    }

    @SuppressWarnings("unchecked")
    private static <T> Class<T> cast(Class<?> type) {
        return (Class<T>) type;
    }

    private static class FakeInventory implements InventoryClient {
        private final Flaky flaky;

        FakeInventory(Flaky flaky) {
            this.flaky = flaky;
        }

        @Override
        public int stock(String sku) {
            flaky.call();
            return 42;
        }

        @Override
        public String fetch(String url) throws IOException {
            flaky.call();
            return "body";
        }

        @Override
        public void reserve(String sku, int quantity) {
            flaky.call();
        }

        @Override
        public int disabled() {
            flaky.call();
            return 0;
        }

        @Override
        public int named() {
            flaky.call();
            return 7;
        }

        @Override
        public <T extends Comparable<T>> T max(List<T> values) {
            flaky.call();
            return values.stream().max(Comparable::compareTo).orElseThrow();
        }

        @Override
        public int unprotected() {
            flaky.call();
            return 1;
        }
    }
}
