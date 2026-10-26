package dev.resilience4cata.examples;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.CircuitBreakerState;
import dev.resilience4cata.Pipeline;
import dev.resilience4cata.PipelineCall;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import dev.resilience4cata.RetryConfig;
import dev.resilience4cata.testkit.ManualClock;
import dev.resilience4cata.testkit.ResilienceTesting;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.IntPredicate;
import org.junit.jupiter.api.Test;

/**
 * Runs the same scripted failures through generated code and through a pipeline with the same
 * configuration, and checks that every call ends the same way.
 */
class PipelineEquivalenceTest {

    private static final String PREFIX = PipelineEquivalentClient.class.getName() + "#";

    private final ResilienceRegistry registry = ResilienceRegistry.builder().build();

    private static PipelineEquivalentClient generated(PipelineLegacyGateway gateway, ResilienceRegistry registry) {
        return Resilient.of(PipelineEquivalentClient.class, new PipelineEquivalentClient() {
            @Override
            public String guarded(String order) throws IOException {
                return gateway.charge(order);
            }

            @Override
            public String classified(String order) throws IOException {
                return gateway.charge(order);
            }

            @Override
            public String limited(String order) throws IOException {
                return gateway.charge(order);
            }

            @Override
            public String everything(String order) throws IOException {
                return gateway.charge(order);
            }

            @Override
            public CompletableFuture<String> everythingAsync(String order) {
                return gateway.chargeAsync(order);
            }
        }, registry);
    }

    private interface Step {
        String run() throws Exception;
    }

    /** Runs {@code count} calls and records how each one ended. */
    private static List<String> trace(int count, Step step) {
        List<String> outcomes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            try {
                outcomes.add(step.run());
            } catch (Exception e) {
                outcomes.add(e.getClass().getSimpleName());
            }
        }
        return outcomes;
    }

    private static Pipeline<String> everythingPipeline(ResilienceRegistry registry, String id) {
        return Pipeline.builder(registry, id, String.class)
                .retry(r -> r.maxAttempts(3).delay(Duration.ZERO).jitter(RetryConfig.Jitter.NONE))
                .circuitBreaker(cb -> cb.windowSize(10).minimumCalls(10).waitInOpen(Duration.ofHours(1)))
                .rateLimiter(rl -> rl.permits(1000))
                .timeout(t -> t.timeout(Duration.ofSeconds(1)))
                .bulkhead(b -> b.maxConcurrent(10))
                .fallbackHandler(new PipelineEquivalentClient.Pending())
                .build();
    }

    @Test
    void retryAndBreakerEndEveryCallTheSameWay() {
        IntPredicate script = call -> call % 4 != 0; // three failures, one success, repeated
        PipelineLegacyGateway forGenerated = new PipelineLegacyGateway(script);
        PipelineLegacyGateway forPipeline = new PipelineLegacyGateway(script);
        PipelineEquivalentClient client = generated(forGenerated, registry);
        Pipeline<String> pipeline = Pipeline.builder(registry, "guarded", String.class)
                .retry(r -> r.maxAttempts(3).delay(Duration.ZERO).jitter(RetryConfig.Jitter.NONE))
                .circuitBreaker(cb -> cb.windowSize(4).minimumCalls(4).failureRateThreshold(50)
                        .waitInOpen(Duration.ofHours(1)))
                .build();

        List<String> expected = trace(6, () -> client.guarded("o"));
        List<String> actual = trace(6, () -> pipeline.execute(() -> forPipeline.charge("o")));

        assertThat(actual).isEqualTo(expected);
        assertThat(expected).as("the breaker opened and stopped the retries")
                .contains("IOException", "CallNotPermittedException");
        assertThat(forPipeline.calls()).isEqualTo(forGenerated.calls());
        assertThat(registry.circuitBreaker("guarded").state())
                .isEqualTo(registry.circuitBreaker(PREFIX + "guarded(java.lang.String)").state())
                .isEqualTo(CircuitBreakerState.OPEN);
    }

    @Test
    void classifiersRetryAndRecordTheSameResults() {
        IntPredicate fails = call -> call % 5 == 0;
        IntPredicate busy = call -> call % 3 != 0;
        PipelineLegacyGateway forGenerated = new PipelineLegacyGateway(fails, busy);
        PipelineLegacyGateway forPipeline = new PipelineLegacyGateway(fails, busy);
        PipelineEquivalentClient client = generated(forGenerated, registry);
        Pipeline<String> pipeline = Pipeline.builder(registry, "classified", String.class)
                .retry(r -> r.maxAttempts(3).delay(Duration.ZERO).jitter(RetryConfig.Jitter.NONE))
                .circuitBreaker(cb -> cb.windowSize(4).minimumCalls(4).failureRateThreshold(50)
                        .waitInOpen(Duration.ofHours(1)))
                .classifier(new PipelineEquivalentClient.Busy())
                .build();

        List<String> expected = trace(6, () -> client.classified("o"));
        List<String> actual = trace(6, () -> pipeline.execute(() -> forPipeline.charge("o")));

        assertThat(actual).isEqualTo(expected);
        assertThat(expected).as("busy answers were retried and opened the breaker")
                .contains("receipt o (call 3)", "CallNotPermittedException");
        assertThat(forPipeline.calls()).isEqualTo(forGenerated.calls());
    }

    @Test
    void theRateLimiterRejectsTheSameCalls() {
        PipelineLegacyGateway forGenerated = new PipelineLegacyGateway(call -> false);
        PipelineLegacyGateway forPipeline = new PipelineLegacyGateway(call -> false);
        PipelineEquivalentClient client = generated(forGenerated, registry);
        Pipeline<String> pipeline = Pipeline.builder(registry, "limited", String.class)
                .rateLimiter(rl -> rl.permits(2).period(Duration.ofHours(1)))
                .build();

        List<String> expected = trace(3, () -> client.limited("o"));
        List<String> actual = trace(3, () -> pipeline.execute(() -> forPipeline.charge("o")));

        assertThat(actual).isEqualTo(expected).last().isEqualTo("RateLimitExceededException");
    }

    @Test
    void theFallbackSeesTheSameAttemptsSyncAndAsync() {
        IntPredicate script = call -> call <= 4; // the first call gives up after three attempts, the second succeeds
        PipelineLegacyGateway forGenerated = new PipelineLegacyGateway(script);
        PipelineLegacyGateway forPipeline = new PipelineLegacyGateway(script);
        PipelineEquivalentClient client = generated(forGenerated, registry);
        Pipeline<String> pipeline = everythingPipeline(registry, "everything");

        List<String> expected = trace(2, () -> client.everything("o"));
        List<String> actual = trace(2, () -> pipeline.execute(() -> forPipeline.charge("o")));

        assertThat(actual).isEqualTo(expected)
                .containsExactly("pending after 3: IOException", "receipt o (call 5)");

        PipelineLegacyGateway asyncForGenerated = new PipelineLegacyGateway(script);
        PipelineLegacyGateway asyncForPipeline = new PipelineLegacyGateway(script);
        PipelineEquivalentClient asyncClient = generated(asyncForGenerated, ResilienceRegistry.builder().build());
        Pipeline<String> asyncPipeline = everythingPipeline(ResilienceRegistry.builder().build(), "everything");
        List<String> expectedAsync = trace(2, () -> asyncClient.everythingAsync("o").get(5, TimeUnit.SECONDS));
        List<String> actualAsync = trace(2, () -> asyncPipeline
                .executeAsync(() -> asyncForPipeline.chargeAsync("o")).get(5, TimeUnit.SECONDS));

        assertThat(actualAsync).isEqualTo(expectedAsync).isEqualTo(expected);
    }

    @Test
    void anOpenBreakerGoesToTheFallbackInBoth() throws IOException {
        PipelineEquivalentClient client = generated(new PipelineLegacyGateway(call -> false), registry);
        Pipeline<String> pipeline = everythingPipeline(registry, "forced");
        PipelineCall<String, IOException> call = () -> new PipelineLegacyGateway(n -> false).charge("o");

        registry.circuitBreaker(PREFIX + "everything(java.lang.String)").forceOpen();
        registry.circuitBreaker("forced").forceOpen();

        assertThat(pipeline.execute(call)).isEqualTo(client.everything("o"))
                .isEqualTo("pending after 1: CallNotPermittedException");
    }

    @Test
    void aHedgeStartsAndCancelsTheSameAttempts() {
        ManualClock pipelineClock = new ManualClock();
        ResilienceRegistry pipelineRegistry = ResilienceTesting.registry(pipelineClock).build();
        HedgeEndToEndTest.Replicas pipelineReplicas = new HedgeEndToEndTest.Replicas();
        Pipeline<String> pipeline = Pipeline.builder(pipelineRegistry, "price", String.class)
                .hedge(h -> h.delay(Duration.ofMillis(50)).maxAttempts(3).budgetPercent(0))
                .circuitBreaker(cb -> cb.windowSize(10).minimumCalls(10))
                .timeout(t -> t.timeout(Duration.ofMillis(200)))
                .fallbackAsyncHandler(new HedgeClient.Cached())
                .build();

        ManualClock clock = new ManualClock();
        HedgeEndToEndTest.Replicas replicas = new HedgeEndToEndTest.Replicas();
        HedgeClient client = Resilient.of(HedgeClient.class, replicas, ResilienceTesting.registry(clock).build());
        CompletableFuture<String> generated = client.price("sku");
        CompletableFuture<String> programmatic = pipeline.executeAsync(() -> pipelineReplicas.price("sku"));
        clock.advance(Duration.ofMillis(50));
        pipelineClock.advance(Duration.ofMillis(50));
        replicas.attempts.get(0).completeExceptionally(new IOException("down"));
        pipelineReplicas.attempts.get(0).completeExceptionally(new IOException("down"));
        clock.advance(Duration.ZERO);
        pipelineClock.advance(Duration.ZERO);
        assertThat(pipelineReplicas.attempts).hasSameSizeAs(replicas.attempts).hasSize(3);
        replicas.attempts.get(2).complete("third");
        pipelineReplicas.attempts.get(2).complete("third");

        assertThat(programmatic).isCompletedWithValue("third");
        assertThat(generated).isCompletedWithValue("third");
        assertThat(pipelineReplicas.attempts.get(1)).isCancelled();
        assertThat(replicas.attempts.get(1)).isCancelled();
    }
}
