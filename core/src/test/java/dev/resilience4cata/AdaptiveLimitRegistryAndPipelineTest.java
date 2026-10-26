package dev.resilience4cata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.resilience4cata.AdaptiveLimitConfig.Algorithm;
import dev.resilience4cata.generated.AdaptiveLimitPolicy;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The adaptive limit's configuration, in the registry (defaults, named configs, overlays, instances, sharing,
 * snapshots) and in {@link Pipeline}.
 */
class AdaptiveLimitRegistryAndPipelineTest {

    @Test
    void theDefaultsAreTheDocumentedOnes() {
        AdaptiveLimitConfig defaults = AdaptiveLimitConfig.DEFAULTS;
        assertThat(defaults.algorithm()).isEqualTo(Algorithm.GRADIENT2);
        assertThat(List.of(defaults.initialLimit(), defaults.minLimit(), defaults.maxLimit())).containsExactly(20, 1, 200);
        assertThat(defaults.window()).isEqualTo(Duration.ofSeconds(1));
        assertThat(defaults.minWindowSamples()).isEqualTo(10);
        assertThat(defaults.dropOn()).containsExactly(CallTimeoutException.class, BulkheadFullException.class,
                java.util.concurrent.TimeoutException.class);
        assertThat(defaults.ignoreOn()).isEmpty();
        assertThat(List.of(defaults.smoothing(), defaults.rttTolerance(), defaults.backoffRatio()))
                .containsExactly(0.2, 1.5, 0.9);
        assertThat(List.of(defaults.longWindow(), defaults.queueSize())).containsExactly(600, 4);
    }

    @Test
    void invalidValuesAreRejectedWithTheirCode() {
        assertInvalid(b -> b.minLimit(0), "minLimit must be at least 1, was 0");
        assertInvalid(b -> b.minLimit(30).maxLimit(10), "maxLimit must be at least minLimit (30), was 10");
        assertInvalid(b -> b.initialLimit(300), "initialLimit must be between minLimit (1) and maxLimit (200), was 300");
        assertInvalid(b -> b.minLimit(5).initialLimit(3), "initialLimit must be between minLimit (5) and maxLimit (200), was 3");
        assertInvalid(b -> b.window(Duration.ofMillis(9)), "window must be between 10ms and 1h, was PT0.009S");
        assertInvalid(b -> b.window(Duration.ofHours(2)), "window must be between 10ms and 1h, was PT2H");
        assertInvalid(b -> b.minWindowSamples(0), "minWindowSamples must be at least 1, was 0");
        assertInvalid(b -> b.smoothing(0), "smoothing must be above 0 and at most 1, was 0.0");
        assertInvalid(b -> b.smoothing(1.5), "smoothing must be above 0 and at most 1, was 1.5");
        assertInvalid(b -> b.rttTolerance(0.9), "rttTolerance must be a finite number >= 1, was 0.9");
        assertInvalid(b -> b.rttTolerance(Double.NaN), "rttTolerance must be a finite number >= 1, was NaN");
        assertInvalid(b -> b.longWindow(0), "longWindow must be at least 1, was 0");
        assertInvalid(b -> b.queueSize(-1), "queueSize must not be negative, was -1");
        assertInvalid(b -> b.backoffRatio(1), "backoffRatio must be at least 0.5 and below 1, was 1.0");
        assertInvalid(b -> b.backoffRatio(0.4), "backoffRatio must be at least 0.5 and below 1, was 0.4");
        assertThat(AdaptiveLimitConfig.builder().minLimit(1).initialLimit(1).maxLimit(1).queueSize(0).smoothing(1)
                .rttTolerance(1).backoffRatio(0.5).window(Duration.ofMillis(10)).build().maxLimit()).isEqualTo(1);
    }

    private static void assertInvalid(java.util.function.Consumer<AdaptiveLimitConfig.Builder> change, String detail) {
        AdaptiveLimitConfig.Builder builder = AdaptiveLimitConfig.builder();
        change.accept(builder);
        assertThatIllegalArgumentException().isThrownBy(builder::build)
                .withMessage("[R4C-REG-001] Invalid adaptiveLimit config: " + detail);
    }

    @Test
    void configsResolveFromDefaultsNamedOverlaysAndInstances() {
        ResilienceRegistry registry = ResilienceRegistry.builder()
                .adaptiveLimitDefaults(a -> a.maxLimit(100))
                .adaptiveLimit("backend", a -> a.algorithm(Algorithm.AIMD))
                .adaptiveLimitOverlay("backend", a -> a.initialLimit(30))
                .adaptiveLimitInstance("orders", a -> a.minLimit(5))
                .build();

        assertThat(registry.adaptiveLimitConfig("").maxLimit()).isEqualTo(100);
        assertThat(registry.adaptiveLimitConfig("backend").algorithm()).isEqualTo(Algorithm.AIMD);
        assertThat(registry.adaptiveLimitConfig("backend").initialLimit()).isEqualTo(30);
        assertThat(registry.adaptiveLimitConfig("backend").maxLimit()).isEqualTo(100);

        AdaptiveLimitPolicy orders = registry.adaptiveLimitPolicy("Api#order()", "orders", "backend", a -> a.minLimit(2));
        assertThat(orders.config().minLimit()).as("the instance override comes last").isEqualTo(5);
        assertThat(registry.adaptiveLimitPolicy("Other#order()", "orders", "backend", a -> a.minLimit(2)))
                .as("shared by name").isSameAs(orders);
        assertThat(registry.adaptiveLimit("orders")).isSameAs(orders);
    }

    @Test
    void invalidUnknownAndConflictingConfigsCarryTheirCodes() {
        assertThatIllegalArgumentException().isThrownBy(() -> ResilienceRegistry.builder()
                .adaptiveLimitDefaults(a -> a.minLimit(0)).build())
                .withMessageStartingWith("[R4C-REG-002] Invalid adaptiveLimit defaults: minLimit must be at least 1")
                .withMessageContaining("defaults.adaptiveLimit.*");
        ResilienceRegistry registry = ResilienceRegistry.builder().adaptiveLimit("backend", a -> { }).build();
        assertThatIllegalArgumentException().isThrownBy(() -> registry.adaptiveLimitConfig("backnd"))
                .withMessageStartingWith("[R4C-REG-004]").withMessageContaining("Did you mean 'backend'?");
        registry.adaptiveLimitPolicy("A#a()", "shared", "", a -> a.maxLimit(50));
        assertThatIllegalStateException().isThrownBy(() -> registry.adaptiveLimitPolicy("B#b()", "shared", "",
                        a -> a.maxLimit(60)))
                .withMessageStartingWith("[R4C-REG-008] Adaptive limit 'shared'");
        assertThatIllegalArgumentException().isThrownBy(() -> registry.adaptiveLimitPolicy("C#c()", "", "",
                a -> a.initialLimit(500))).withMessageStartingWith("[R4C-REG-003]");
        assertThatIllegalArgumentException().isThrownBy(() -> registry.adaptiveLimit("sharde"))
                .withMessageStartingWith("[R4C-REG-009] No adaptive limit 'sharde'").withMessageContaining("'shared'");
    }

    @Test
    void theSnapshotListsAdaptiveLimitsSortedById() {
        ResilienceRegistry registry = ResilienceRegistry.builder().build();
        AtomicInteger created = new AtomicInteger();
        registry.onInstanceCreated(created::incrementAndGet);
        registry.adaptiveLimitPolicy("B#b()", "", "", a -> a.algorithm(Algorithm.AIMD));
        registry.adaptiveLimitPolicy("A#a()", "", "", a -> a.initialLimit(7)).acquire();

        List<AdaptiveLimitSnapshot> limits = registry.snapshot().adaptiveLimits();
        assertThat(limits).extracting(AdaptiveLimitSnapshot::instance).containsExactly("A#a()", "B#b()");
        assertThat(limits.get(0)).isEqualTo(new AdaptiveLimitSnapshot("A#a()", Algorithm.GRADIENT2, 7, 1, 1, 200, 0,
                Duration.ZERO, Duration.ZERO));
        assertThat(registry.adaptiveLimit("A#a()").snapshot()).isEqualTo(limits.get(0));
        assertThat(created).as("instance creation is announced").hasValue(2);
        assertThat(new ResilienceSnapshot(List.of(), List.of(), List.of(), List.of(), List.of(), List.of()).adaptiveLimits())
                .as("the 0.5.0 constructor").isEmpty();
    }

    @Test
    void aPipelineRejectsWhenFullAndFreesTheSlot() throws Exception {
        ResilienceRegistry registry = ResilienceRegistry.builder().build();
        Pipeline<String> pipeline = Pipeline.builder(registry, "limited", String.class)
                .adaptiveLimit(a -> a.initialLimit(1))
                .build();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = Thread.ofVirtual().start(() -> {
            try {
                pipeline.execute(() -> {
                    entered.countDown();
                    release.await();
                    return "held";
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> pipeline.execute(() -> "ok")).isInstanceOf(LimitExceededException.class);
        release.countDown();
        holder.join();
        assertThat(registry.adaptiveLimit("limited").inFlight()).isZero();
        assertThatThrownBy(() -> pipeline.execute(() -> {
            throw new IOException("boom");
        })).isInstanceOf(IOException.class);
        assertThat(registry.adaptiveLimit("limited").inFlight()).isZero();
        assertThat(pipeline.toString()).contains("adaptiveLimit");
    }

    @Test
    void aPipelineLimitsAsyncCallsUntilTheirStageCompletes() {
        ResilienceRegistry registry = ResilienceRegistry.builder().build();
        Pipeline<String> pipeline = Pipeline.builder(registry, "async-limited", String.class)
                .adaptiveLimit(a -> a.initialLimit(1))
                .timeout(t -> t.timeout(Duration.ofSeconds(30)))
                .build();
        CompletableFuture<String> slow = new CompletableFuture<>();
        CompletableFuture<String> first = pipeline.executeAsync(() -> slow);
        assertThat(pipeline.executeAsync(() -> CompletableFuture.completedFuture("x")))
                .failsWithin(Duration.ZERO).withThrowableThat().havingCause().isInstanceOf(LimitExceededException.class);
        slow.complete("done");
        assertThat(first).isCompletedWithValue("done");
        assertThat(registry.adaptiveLimit("async-limited").inFlight()).isZero();
    }

    @Test
    void theSyncSuccessPathDoesNotAllocate() {
        ResilienceRegistry registry = ResilienceRegistry.builder().build();
        Pipeline<String> pipeline = Pipeline.builder(registry, "allocation", String.class)
                .circuitBreaker()
                .adaptiveLimit(a -> a.window(Duration.ofMillis(10)).minWindowSamples(1))
                .bulkhead()
                .build();
        PipelineCall<String, RuntimeException> call = () -> "ok";
        for (int i = 0; i < 20_000; i++) {
            pipeline.execute(call);
        }

        Allocations.Measurement measured = Allocations.steadyState(() -> {
            for (int i = 0; i < 100_000; i++) {
                pipeline.execute(call);
            }
        });

        assertThat(measured.bytes()).as("bytes allocated by 100k calls, windows ending every 10 ms included")
                .isLessThan(Allocations.LIMIT);
        assertThat(registry.adaptiveLimit("allocation").snapshot().windows()).isPositive();
    }
}
