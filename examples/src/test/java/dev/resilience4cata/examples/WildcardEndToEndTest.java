package dev.resilience4cata.examples;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.resilience4cata.CallNotPermittedException;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WildcardEndToEndTest {

    private final ResilienceRegistry registry = ResilienceRegistry.builder().build();
    private final AtomicInteger countCalls = new AtomicInteger();
    private final AtomicInteger nameCalls = new AtomicInteger();
    private final AtomicInteger itemsCalls = new AtomicInteger();

    private final WildcardClient client = Resilient.of(WildcardClient.class, new WildcardClient() {
        @Override
        public CompletionStage<? extends Number> count(String id) {
            int call = countCalls.incrementAndGet();
            if (id.equals("fail") || call == 1) {
                return CompletableFuture.failedFuture(new IllegalStateException("down"));
            }
            return CompletableFuture.completedFuture(Long.valueOf(call));
        }

        @Override
        public CompletableFuture<?> anything(String id) {
            return id.equals("fail") ? CompletableFuture.failedFuture(new IllegalStateException("down"))
                    : CompletableFuture.completedFuture(List.of(id));
        }

        @Override
        public CompletionStage<? extends CharSequence> name(String id) {
            nameCalls.incrementAndGet();
            return CompletableFuture.failedFuture(new IllegalStateException("down"));
        }

        @Override
        public <T> CompletableFuture<? extends List<T>> items(Class<T> type) {
            itemsCalls.incrementAndGet();
            throw new IllegalStateException("thrown before returning a stage");
        }
    }, registry);

    @Test
    void aWildcardStageIsRetriedAndKeepsTheDelegatesValue() throws Exception {
        Number value = client.count("ok").toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertThat(value).isEqualTo(2L);
        assertThat(countCalls).hasValue(2);
    }

    // java:S5838: the value is a Number, which has no isZero(); equality also checks that it is an Integer
    @SuppressWarnings("java:S5838")
    @Test
    void aWildcardStageFallsBackToAValueOfTheUpperBound() throws Exception {
        Number value = client.count("fail").toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertThat(value).isEqualTo(0);
        assertThat(countCalls).hasValue(3);
    }

    @Test
    void anUnboundedWildcardGoesThroughTheBreakerAndTheTimeout() throws Exception {
        Object value = client.anything("a").get(5, TimeUnit.SECONDS);
        assertThat(value).isEqualTo(List.of("a"));

        registry.circuitBreaker("dev.resilience4cata.examples.WildcardClient#anything(java.lang.String)").forceOpen();

        assertThatThrownBy(() -> client.anything("a").get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(CallNotPermittedException.class);
    }

    @Test
    void aHandlerReturningAStageOfASubtype() throws Exception {
        CharSequence name = client.name("n").toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertThat(name).hasToString("cached n after 2");
        assertThat(nameCalls).hasValue(2);
    }

    @Test
    void aGenericWildcardMethodWithAWildcardFallback() throws Exception {
        List<String> items = client.items(String.class).get(5, TimeUnit.SECONDS);

        assertThat(items).isEmpty();
        assertThat(itemsCalls).hasValue(2);
    }
}
