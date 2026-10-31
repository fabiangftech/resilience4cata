package dev.resilience4cata.reactor;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.Pipeline;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.RetryConfig.Backoff;
import dev.resilience4cata.RetryConfig.Jitter;
import dev.resilience4cata.event.ResilienceEvent;
import dev.resilience4cata.testkit.ManualClock;
import dev.resilience4cata.testkit.ResilienceTesting;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Differential test: a {@code Mono} through {@link MonoPipelineOperator} behaves exactly like a
 * {@code CompletableFuture} through {@link Pipeline#executeAsync}. Each seed builds a random pipeline
 * (retry or hedge, breaker, timeout, bulkhead, fallback) and a random script of attempt outcomes
 * (values, empty results, failures, slow and never-ending calls), runs several overlapping calls in two
 * identical worlds on manual clocks, and compares every call's outcome, every event the policies
 * published, and the policies' state afterwards.
 */
class MonoEquivalenceTest {

    private static final int CALLS = 4;
    private static final int ATTEMPTS_PER_CALL = 8;

    /** How many seeds saw each situation: checked at the end, so that the seeds keep covering all of them. */
    private static final Map<String, Integer> COVERAGE = new ConcurrentHashMap<>();

    @AfterAll
    static void theSeedsCoverEverySituation() {
        assertThat(COVERAGE).as("seeds per situation").allSatisfy((situation, seeds) ->
                assertThat(seeds).as(situation).isGreaterThanOrEqualTo(10));
        assertThat(COVERAGE.keySet()).containsExactlyInAnyOrder("cancelled attempt", "TimedOut", "CallNotPermitted",
                "BulkheadFullException", "fallback", "Hedged", "Retrying", "GaveUp", "StateTransition", "value:null");
    }

    private static void cover(World world) {
        List<String> seen = new ArrayList<>();
        if (!world.cancelled.isEmpty()) {
            seen.add("cancelled attempt");
        }
        for (String event : List.of("TimedOut", "CallNotPermitted", "Hedged", "Retrying", "GaveUp", "StateTransition")) {
            if (world.events.stream().anyMatch(e -> e.startsWith(event + "["))) {
                seen.add(event);
            }
        }
        for (String result : List.of("BulkheadFullException", "fallback", "value:null")) {
            if (Arrays.stream(world.results).anyMatch(r -> r.contains(result))) {
                seen.add(result);
            }
        }
        seen.forEach(situation -> COVERAGE.merge(situation, 1, Integer::sum));
    }

    static IntStream seeds() {
        return IntStream.range(0, 400);
    }

    /** What one attempt does. */
    enum Outcome {
        VALUE, EMPTY, IO_FAILURE, STATE_FAILURE, SLOW_VALUE, SLOW_FAILURE, NEVER
    }

    /** A random pipeline shape, the same in both worlds. */
    record Shape(int retryAttempts, int hedgeAttempts, boolean breaker, boolean timeout, int bulkhead,
            boolean fallback) {

        static Shape random(SplittableRandom random) {
            boolean hedge = random.nextInt(4) == 0;
            return new Shape(hedge || random.nextInt(4) == 0 ? 0 : 1 + random.nextInt(4),
                    hedge ? 2 + random.nextInt(2) : 0,
                    random.nextBoolean(), random.nextBoolean(),
                    random.nextInt(3) == 0 ? 1 + random.nextInt(2) : 0,
                    random.nextInt(3) == 0);
        }

        Pipeline<String> build(ResilienceRegistry registry) {
            Pipeline.Builder<String> builder = Pipeline.builder(registry, "equivalence", String.class);
            if (retryAttempts > 0) {
                builder.retry(r -> r.maxAttempts(retryAttempts).delay(Duration.ofMillis(10))
                        .backoff(Backoff.FIXED).jitter(Jitter.NONE));
            }
            if (hedgeAttempts > 0) {
                builder.hedge(h -> h.maxAttempts(hedgeAttempts).delay(Duration.ofMillis(30)).budgetPercent(100));
            }
            if (breaker) {
                builder.circuitBreaker(b -> b.windowSize(4).minimumCalls(2).failureRateThreshold(50)
                        .waitInOpen(Duration.ofMillis(500)));
            }
            if (timeout) {
                builder.timeout(t -> t.timeout(Duration.ofMillis(100)));
            }
            if (bulkhead > 0) {
                builder.bulkhead(b -> b.maxConcurrent(bulkhead));
            }
            if (fallback) {
                builder.fallback(failure -> "fallback:" + failure.getClass().getSimpleName());
            }
            return builder.build();
        }
    }

    /** One world: its clock, registry, events and the outcome of each call. */
    private static final class World {
        final ManualClock clock = new ManualClock();
        final List<String> events = new ArrayList<>();
        final ResilienceRegistry registry = ResilienceTesting.registry(clock)
                .listen(ResilienceEvent.class, event -> events.add(event.toString()))
                .build();
        final String[] results = new String[CALLS];
        /** The attempts that the policies cancelled, as "call/attempt", in the order they were cancelled. */
        final List<String> cancelled = new ArrayList<>();

        void record(int call, String outcome) {
            assertThat(results[call]).as("call %d ends once", call).isNull();
            results[call] = outcome;
        }

        CompletableFuture<String> later(long millis, Function<CompletableFuture<String>, Boolean> completion) {
            CompletableFuture<String> future = new CompletableFuture<>();
            clock.schedule(Duration.ofMillis(millis).toNanos(), () -> completion.apply(future));
            return future;
        }
    }

    private static String describe(Throwable failure) {
        return "error:" + failure.getClass().getSimpleName() + ":" + failure.getMessage();
    }

    @ParameterizedTest(name = "seed {0}")
    @MethodSource("seeds")
    void aMonoThroughThePipelineBehavesLikeAFuture(int seed) {
        // SplittableRandom: java.util.Random's first values for consecutive seeds are nearly the same.
        SplittableRandom random = new SplittableRandom(seed);
        Shape shape = Shape.random(random);
        Outcome[][] script = new Outcome[CALLS][ATTEMPTS_PER_CALL];
        for (Outcome[] attempts : script) {
            for (int i = 0; i < attempts.length; i++) {
                Outcome outcome = Outcome.values()[random.nextInt(Outcome.values().length)];
                // A call that never ends is only allowed when a timeout ends it.
                attempts[i] = outcome == Outcome.NEVER && !shape.timeout() ? Outcome.SLOW_VALUE : outcome;
            }
        }

        World futures = new World();
        Pipeline<String> futurePipeline = shape.build(futures.registry);
        for (int call = 0; call < CALLS; call++) {
            int c = call;
            AtomicInteger attempt = new AtomicInteger();
            futurePipeline.executeAsync(() -> futureAttempt(futures, script[c], c, attempt.getAndIncrement()))
                    .whenComplete((value, failure) -> futures.record(c, failure == null ? "value:" + value
                            : describe(failure)));
        }
        futures.clock.advanceUntilIdle();

        World monos = new World();
        MonoPipelineOperator<String> operator = MonoPipelineOperator.of(shape.build(monos.registry));
        for (int call = 0; call < CALLS; call++) {
            int c = call;
            AtomicInteger attempt = new AtomicInteger();
            operator.defer(() -> monoAttempt(monos, script[c], c, attempt.getAndIncrement())).subscribe(
                    value -> monos.record(c, "value:" + value),
                    failure -> monos.record(c, describe(failure)),
                    () -> {
                        if (monos.results[c] == null) {
                            monos.record(c, "value:null");
                        }
                    });
        }
        monos.clock.advanceUntilIdle();

        String context = "seed " + seed + ", " + shape;
        assertThat(monos.results).as("outcomes, " + context).containsExactly(futures.results);
        assertThat(monos.results).as("every call ended, " + context).doesNotContainNull();
        assertThat(monos.events).as("events, " + context).containsExactlyElementsOf(futures.events);
        assertThat(monos.cancelled).as("cancelled attempts, " + context).containsExactlyElementsOf(futures.cancelled);
        assertThat(monos.registry.snapshot().toString()).as("state, " + context)
                .isEqualTo(futures.registry.snapshot().toString());
        assertThat(monos.clock.pendingTasks()).as("timers, " + context).isZero();
        cover(monos);
    }

    private static CompletionStage<String> futureAttempt(World world, Outcome[] script, int call, int attempt) {
        String value = "v" + call;
        CompletableFuture<String> stage = switch (script[attempt]) {
            case VALUE -> CompletableFuture.completedFuture(value);
            case EMPTY -> CompletableFuture.completedFuture(null);
            case IO_FAILURE -> CompletableFuture.failedFuture(new IOException("io " + call));
            case STATE_FAILURE -> CompletableFuture.failedFuture(new IllegalStateException("state " + call));
            case SLOW_VALUE -> world.later(150, future -> future.complete(value));
            case SLOW_FAILURE -> world.later(60, future -> future.completeExceptionally(new IOException("slow " + call)));
            case NEVER -> new CompletableFuture<>();
        };
        stage.whenComplete((v, failure) -> {
            if (stage.isCancelled()) {
                world.cancelled.add(call + "/" + attempt);
            }
        });
        return stage;
    }

    private static Mono<String> monoAttempt(World world, Outcome[] script, int call, int attempt) {
        String value = "v" + call;
        Mono<String> mono = switch (script[attempt]) {
            case VALUE -> Mono.just(value);
            case EMPTY -> Mono.empty();
            case IO_FAILURE -> Mono.error(new IOException("io " + call));
            case STATE_FAILURE -> Mono.error(new IllegalStateException("state " + call));
            case SLOW_VALUE -> emitLater(world, 150, sink -> sink.tryEmitValue(value));
            case SLOW_FAILURE -> emitLater(world, 60, sink -> sink.tryEmitError(new IOException("slow " + call)));
            case NEVER -> Mono.never();
        };
        return mono.doOnCancel(() -> world.cancelled.add(call + "/" + attempt));
    }

    private static Mono<String> emitLater(World world, long millis, Function<Sinks.One<String>, Sinks.EmitResult> emit) {
        Sinks.One<String> sink = Sinks.one();
        world.clock.schedule(Duration.ofMillis(millis).toNanos(), () -> emit.apply(sink));
        return sink.asMono();
    }
}
