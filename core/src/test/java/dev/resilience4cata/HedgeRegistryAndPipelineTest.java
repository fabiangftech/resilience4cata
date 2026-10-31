package dev.resilience4cata;

import dev.resilience4cata.generated.Policies;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import dev.resilience4cata.generated.HedgePolicy;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** The hedge in the registry (defaults, named configs, overlays, instances, sharing, snapshots) and in {@link Pipeline}. */
class HedgeRegistryAndPipelineTest {

    @Test
    void configsResolveFromDefaultsNamedOverlaysAndInstances() {
        ResilienceRegistry registry = ResilienceRegistry.builder()
                .hedgeDefaults(h -> h.delay(Duration.ofMillis(80)))
                .hedge("reads", h -> h.maxAttempts(3))
                .hedgeOverlay("reads", h -> h.budgetPercent(5))
                .hedgeInstance("quotes", h -> h.maxAttempts(4))
                .build();

        assertThat(registry.hedgeConfig("").delay()).isEqualTo(Duration.ofMillis(80));
        assertThat(registry.hedgeConfig("reads").maxAttempts()).isEqualTo(3);
        assertThat(registry.hedgeConfig("reads").budgetPercent()).isEqualTo(5);
        assertThat(registry.hedgeConfig("reads").delay()).isEqualTo(Duration.ofMillis(80));

        HedgePolicy quotes = Policies.hedge(registry, "Api#quote()", "quotes", "reads", h -> h.delay(Duration.ofMillis(20)), false);
        assertThat(quotes.config().maxAttempts()).as("the instance override comes last").isEqualTo(4);
        assertThat(quotes.config().delay()).isEqualTo(Duration.ofMillis(20));
        assertThat(Policies.hedge(registry, "Other#quote()", "quotes", "reads", h -> h.delay(Duration.ofMillis(20)), false))
                .as("shared by name").isSameAs(quotes);
    }

    @Test
    void invalidAndConflictingConfigsCarryTheirCodes() {
        assertThatIllegalArgumentException().isThrownBy(() -> ResilienceRegistry.builder()
                .hedgeDefaults(h -> h.maxAttempts(1)).build()).withMessageStartingWith("[R4C-REG-002]")
                .withMessageContaining("defaults.hedge.*");
        assertThatIllegalArgumentException().isThrownBy(() -> ResilienceRegistry.builder().build().hedgeConfig("redas"))
                .withMessageStartingWith("[R4C-REG-004]");
        ResilienceRegistry registry = ResilienceRegistry.builder().build();
        Policies.hedge(registry, "A#a()", "shared", "", h -> h.maxAttempts(2), false);
        assertThatIllegalStateException().isThrownBy(() -> Policies.hedge(registry, "B#b()", "shared", "", h -> h.maxAttempts(3), false))
                .withMessageStartingWith("[R4C-REG-008] Hedge instance 'shared'");
        assertThatIllegalArgumentException().isThrownBy(() -> Policies.hedge(registry, "C#c()", "", "", h -> h.maxAttempts(12), false))
                .withMessageStartingWith("[R4C-REG-003]");
    }

    @Test
    void theSnapshotListsHedgesWithTheirBudget() {
        ResilienceRegistry registry = ResilienceRegistry.builder().build();
        AtomicInteger created = new AtomicInteger();
        registry.onInstanceCreated(created::incrementAndGet);
        Policies.hedge(registry, "B#b()", "", "", h -> h.budgetPercent(0), false);
        Policies.hedge(registry, "A#a()", "", "", null, false);

        List<HedgeSnapshot> hedges = registry.snapshot().hedges();
        assertThat(hedges).extracting(HedgeSnapshot::instance).containsExactly("A#a()", "B#b()");
        assertThat(hedges.get(0).budget()).isEqualTo(new HedgeSnapshot.Budget(0, 0, 10));
        assertThat(hedges.get(1).budget()).as("no budget").isNull();
        assertThat(created).as("instance creation is announced").hasValue(2);
    }

    @Test
    void aPipelineHedgesAsyncCalls() {
        ResilienceRegistry registry = ResilienceRegistry.builder().build();
        Pipeline<String> pipeline = Pipeline.builder(registry, "quotes", String.class)
                .hedge(h -> h.delay(Duration.ZERO))
                .circuitBreaker()
                .build();
        AtomicInteger calls = new AtomicInteger();
        CompletableFuture<String> slow = new CompletableFuture<>();

        CompletableFuture<String> result = pipeline.executeAsync(() -> calls.incrementAndGet() == 1 ? slow
                : CompletableFuture.completedFuture("hedge"));

        assertThat(result).isCompletedWithValue("hedge");
        assertThat(slow).isCancelled();
        assertThat(calls).hasValue(2);
        assertThat(pipeline).hasToString("Pipeline[quotes, hedge, circuitBreaker]");
        assertThat(registry.snapshot().hedges()).extracting(HedgeSnapshot::instance).containsExactly("quotes");
        assertThat(registry.circuitBreaker("quotes").snapshot().failedCalls()).as("the loser is not recorded").isZero();
    }

    @Test
    void aPipelineFallbackGetsTheAttemptsOfTheHedge() {
        Pipeline<String> pipeline = Pipeline.builder(ResilienceRegistry.builder().build(), "quotes", String.class)
                .hedge(h -> h.delay(Duration.ZERO).maxAttempts(3))
                .fallbackHandler(context -> "fallback after " + context.attempts())
                .build();

        CompletableFuture<String> result = pipeline.executeAsync(
                () -> CompletableFuture.failedFuture(new java.io.IOException("down")));

        assertThat(result).isCompletedWithValue("fallback after 3");
    }

    @Test
    void aHedgeIsAsyncOnlyAndExclusiveWithARetry() {
        Pipeline<String> pipeline = Pipeline.builder(ResilienceRegistry.builder().build(), "quotes", String.class)
                .hedge()
                .build();
        assertThatIllegalStateException().isThrownBy(() -> pipeline.execute(() -> "sync"))
                .withMessageStartingWith("[R4C-RT-018] Pipeline 'quotes' has a hedge, which only works with executeAsync");
        assertThatIllegalStateException().isThrownBy(() -> pipeline.run(() -> { }))
                .withMessageStartingWith("[R4C-RT-018]");

        Pipeline.Builder<String> both = Pipeline.builder(ResilienceRegistry.builder().build(), "quotes", String.class)
                .retry().hedge("");
        assertThatIllegalStateException().isThrownBy(both::build)
                .withMessageStartingWith("[R4C-RT-019] Pipeline 'quotes' has both a retry and a hedge");
        assertThatIllegalStateException().isThrownBy(() -> Pipeline.builder(ResilienceRegistry.builder().build(), "q")
                .hedge().hedge()).withMessageStartingWith("[R4C-RT-012]");
    }
}
