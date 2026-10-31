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
 * Differential test: a {@code Flux} of at most one element through {@link FluxPipelineOperator} behaves
 * exactly like the same {@code Mono} through {@link MonoPipelineOperator}. With one element that arrives
 * together with the end, the stream rules (retry and fallback before the first element only, a timeout
 * restarted by every element, slow calls and latency measured to the first element) say the same as the
 * {@code Mono} ones, so any difference is a bug in the stream machinery. Each seed builds a random
 * pipeline (retry, breaker with or without slow-call detection, rate limiter, adaptive limit, timeout,
 * bulkhead, fallback) and a random script of attempt outcomes, runs several overlapping calls in two
 * identical worlds on manual clocks, and compares every call's outcome, every event, the attempts the
 * policies cancelled, and the policies' state afterwards.
 */
class FluxEquivalenceTest {

    private static final int CALLS = 4;
    private static final int ATTEMPTS_PER_CALL = 8;

    /** How many seeds saw each situation: checked at the end, so that the seeds keep covering all of them. */
    private static final Map<String, Integer> COVERAGE = new ConcurrentHashMap<>();

    @AfterAll
    static void theSeedsCoverEverySituation() {
        assertThat(COVERAGE).as("seeds per situation").allSatisfy((situation, seeds) ->
                assertThat(seeds).as(situation).isGreaterThanOrEqualTo(10));
        assertThat(COVERAGE.keySet()).containsExactlyInAnyOrder("cancelled attempt", "TimedOut", "CallNotPermitted",
                "BulkheadFullException", "RateLimitExceededException", "AdaptiveLimitFullException", "fallback", "Retrying",
                "GaveUp", "StateTransition", "slow call", "value:null");
    }

    private static void cover(World world) {
        List<String> seen = new ArrayList<>();
        if (!world.cancelled.isEmpty()) {
            seen.add("cancelled attempt");
        }
        for (String event : List.of("TimedOut", "CallNotPermitted", "Retrying", "GaveUp", "StateTransition")) {
            if (world.events.stream().anyMatch(e -> e.startsWith(event + "["))) {
                seen.add(event);
            }
        }
        for (String result : List.of("BulkheadFullException", "RateLimitExceededException", "AdaptiveLimitFullException",
                "fallback", "value:null")) {
            if (Arrays.stream(world.results).anyMatch(r -> r.contains(result))) {
                seen.add(result);
            }
        }
        if (world.registry.snapshot().circuitBreakers().stream().anyMatch(b -> b.slowCalls() > 0)) {
            seen.add("slow call");
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
    record Shape(int retryAttempts, boolean breaker, boolean slowCalls, boolean rateLimiter, boolean adaptiveLimit,
            boolean timeout, int bulkhead, boolean fallback) {

        static Shape random(SplittableRandom random) {
            return new Shape(random.nextInt(4) == 0 ? 0 : 1 + random.nextInt(4),
                    random.nextBoolean(), random.nextBoolean(), random.nextInt(4) == 0, random.nextInt(4) == 0,
                    random.nextBoolean(), random.nextInt(3) == 0 ? 1 + random.nextInt(2) : 0, random.nextInt(3) == 0);
        }

        Pipeline<String> build(ResilienceRegistry registry) {
            Pipeline.Builder<String> builder = Pipeline.builder(registry, "equivalence", String.class);
            if (retryAttempts > 0) {
                builder.retry(r -> r.maxAttempts(retryAttempts).delay(Duration.ofMillis(10))
                        .backoff(Backoff.FIXED).jitter(Jitter.NONE));
            }
            if (breaker) {
                builder.circuitBreaker(b -> {
                    b.windowSize(4).minimumCalls(2).failureRateThreshold(50).waitInOpen(Duration.ofMillis(500));
                    if (slowCalls) {
                        b.slowCallDuration(Duration.ofMillis(50)).slowCallRateThreshold(100);
                    }
                });
            }
            if (rateLimiter) {
                builder.rateLimiter(l -> l.permits(3).period(Duration.ofMillis(100)).maxWait(Duration.ofMillis(40)));
            }
            if (adaptiveLimit) {
                builder.adaptiveLimit(l -> l.initialLimit(2).minLimit(1).maxLimit(4).window(Duration.ofMillis(50))
                        .minWindowSamples(1));
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

        void complete(int call) {
            if (results[call] == null) {
                record(call, "value:null");
            }
        }
    }

    private static String describe(Throwable failure) {
        return "error:" + failure.getClass().getSimpleName() + ":" + failure.getMessage();
    }

    @ParameterizedTest(name = "seed {0}")
    @MethodSource("seeds")
    void aFluxOfOneElementBehavesLikeAMono(int seed) {
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

        World monos = new World();
        MonoPipelineOperator<String> monoOperator = MonoPipelineOperator.of(shape.build(monos.registry));
        for (int call = 0; call < CALLS; call++) {
            int c = call;
            AtomicInteger attempt = new AtomicInteger();
            monoOperator.defer(() -> attempt(monos, script[c], c, attempt.getAndIncrement())).subscribe(
                    value -> monos.record(c, "value:" + value),
                    failure -> monos.record(c, describe(failure)),
                    () -> monos.complete(c));
        }
        monos.clock.advanceUntilIdle();

        World fluxes = new World();
        FluxPipelineOperator<String> fluxOperator = FluxPipelineOperator.of(shape.build(fluxes.registry));
        for (int call = 0; call < CALLS; call++) {
            int c = call;
            AtomicInteger attempt = new AtomicInteger();
            fluxOperator.defer(() -> attempt(fluxes, script[c], c, attempt.getAndIncrement()).flux()).subscribe(
                    value -> fluxes.record(c, "value:" + value),
                    failure -> fluxes.record(c, describe(failure)),
                    () -> fluxes.complete(c));
        }
        fluxes.clock.advanceUntilIdle();

        String context = "seed " + seed + ", " + shape;
        assertThat(fluxes.results).as("outcomes, " + context).containsExactly(monos.results);
        assertThat(fluxes.results).as("every call ended, " + context).doesNotContainNull();
        assertThat(fluxes.events).as("events, " + context).containsExactlyElementsOf(monos.events);
        assertThat(fluxes.cancelled).as("cancelled attempts, " + context).containsExactlyElementsOf(monos.cancelled);
        assertThat(fluxes.registry.snapshot().toString()).as("state, " + context)
                .isEqualTo(monos.registry.snapshot().toString());
        assertThat(fluxes.clock.pendingTasks()).as("timers, " + context).isZero();
        cover(fluxes);
    }

    private static Mono<String> attempt(World world, Outcome[] script, int call, int attempt) {
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
