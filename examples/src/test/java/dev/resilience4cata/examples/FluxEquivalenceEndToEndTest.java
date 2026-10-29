package dev.resilience4cata.examples;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import dev.resilience4cata.event.ResilienceEvent;
import dev.resilience4cata.testkit.ManualClock;
import dev.resilience4cata.testkit.ResilienceTesting;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Differential test over generated code: for each shape of {@link FluxTwins}, the {@code Flux} twin, given
 * streams of at most one element that ends with it, behaves exactly like the {@code Mono} twin. For such
 * streams the stream rules (retry and fallback before the first element only, a timeout restarted by every
 * element, slow calls and latency measured to the first element) say what the {@code Mono} rules say, so
 * any difference is a bug in the generated stream code or the stream machinery. Each seed scripts the
 * outcome of every attempt (values, blank and empty results, failures, slow and never-ending calls), starts
 * several overlapping calls in two identical worlds on manual clocks, and compares the outcome of every
 * call, every event (with the method name normalized), which attempts the policies cancelled, and the
 * registry's snapshot.
 */
class FluxEquivalenceEndToEndTest {

    private static final int SEEDS_PER_SHAPE = 100;
    private static final int CALLS = 4;
    private static final int ATTEMPTS = 8;

    private static final List<Class<? extends FluxTwins.Twin>> SHAPES = List.of(
            FluxTwins.RetryAndTimeout.class, FluxTwins.RetryBreakerFallback.class, FluxTwins.SlowCalls.class,
            FluxTwins.FallbackTimeoutBulkhead.class, FluxTwins.LimitedRetry.class, FluxTwins.Adaptive.class);

    /** How many seeds of each shape saw each situation; checked at the end so the seeds keep covering them. */
    private static final Map<String, Integer> COVERAGE = new ConcurrentHashMap<>();

    static Stream<Arguments> cases() {
        return SHAPES.stream().flatMap(shape -> java.util.stream.IntStream.range(0, SEEDS_PER_SHAPE)
                .mapToObj(seed -> Arguments.of(shape.getSimpleName(), shape, seed)));
    }

    enum Outcome {
        VALUE, BLANK, EMPTY, IO_FAILURE, STATE_FAILURE, SLOW_VALUE, SLOW_FAILURE, NEVER
    }

    /** One world: a clock, a registry, what it published, and how each call ended. */
    static final class World {
        final ManualClock clock = new ManualClock();
        final List<String> events = new ArrayList<>();
        final ResilienceRegistry registry = ResilienceTesting.registry(clock)
                .listen(ResilienceEvent.class, event -> events.add(normalize(event.toString())))
                .build();
        final String[] results = new String[CALLS];
        final List<String> cancelled = new ArrayList<>();

        void record(int call, String outcome) {
            assertThat(results[call]).as("call %d ends once", call).isNull();
            results[call] = outcome;
        }

        void later(long millis, Runnable task) {
            clock.schedule(Duration.ofMillis(millis).toNanos(), task);
        }
    }

    /** The two twins' generated ids differ only in the method name. */
    private static String normalize(String text) {
        return text.replace("#mono(int)", "#twin(int)").replace("#flux(int)", "#twin(int)");
    }

    /** The delegate of every shape: each attempt of each call does what the script says, as a Mono or a Flux. */
    static final class Backend implements FluxTwins.RetryAndTimeout, FluxTwins.RetryBreakerFallback,
            FluxTwins.SlowCalls, FluxTwins.FallbackTimeoutBulkhead, FluxTwins.LimitedRetry, FluxTwins.Adaptive {

        private final World world;
        private final Outcome[][] script;
        private final int[] attempts = new int[CALLS];

        Backend(World world, Outcome[][] script) {
            this.world = world;
            this.script = script;
        }

        @Override
        public Mono<String> mono(int call) {
            return attempt(call);
        }

        @Override
        public Flux<String> flux(int call) {
            return attempt(call).flux();
        }

        private Mono<String> attempt(int call) {
            int attempt = attempts[call]++;
            Mono<String> mono = switch (script[call][attempt]) {
                case VALUE -> Mono.just("v" + call);
                case BLANK -> Mono.just(" ");
                case EMPTY -> Mono.empty();
                case IO_FAILURE -> Mono.error(new IOException("io " + call));
                case STATE_FAILURE -> Mono.error(new IllegalStateException("state " + call));
                case SLOW_VALUE -> emitLater(150, sink -> sink.tryEmitValue("slow v" + call));
                case SLOW_FAILURE -> emitLater(60, sink -> sink.tryEmitError(new IOException("slow " + call)));
                case NEVER -> Mono.never();
            };
            return mono.doOnCancel(() -> world.cancelled.add(call + "/" + attempt));
        }

        private Mono<String> emitLater(long millis, Consumer<Sinks.One<String>> emit) {
            Sinks.One<String> sink = Sinks.one();
            world.later(millis, () -> emit.accept(sink));
            return sink.asMono();
        }
    }

    private static String describe(Throwable failure) {
        return normalize("error:" + failure.getClass().getSimpleName() + ":" + failure.getMessage());
    }

    @ParameterizedTest(name = "{0}, seed {2}")
    @MethodSource("cases")
    void theFluxTwinBehavesLikeTheMonoTwin(String name, Class<? extends FluxTwins.Twin> shape, int seed) {
        SplittableRandom random = new SplittableRandom(seed * 31L + name.hashCode());
        // A shape without a timeout would wait forever for an attempt that never ends.
        boolean timed = shape != FluxTwins.RetryBreakerFallback.class;
        Outcome[][] script = new Outcome[CALLS][ATTEMPTS];
        for (Outcome[] attempts : script) {
            for (int i = 0; i < attempts.length; i++) {
                Outcome outcome = Outcome.values()[random.nextInt(Outcome.values().length)];
                attempts[i] = outcome == Outcome.NEVER && !timed ? Outcome.SLOW_VALUE : outcome;
            }
        }

        World monos = run(shape, script, (client, call) -> client.mono(call).flux());
        World fluxes = run(shape, script, FluxTwins.Twin::flux);

        String context = name + ", seed " + seed;
        assertThat(fluxes.results).as("outcomes, " + context).containsExactly(monos.results);
        assertThat(fluxes.results).as("every call ended, " + context).doesNotContainNull();
        assertThat(fluxes.events).as("events, " + context).containsExactlyElementsOf(monos.events);
        assertThat(fluxes.cancelled).as("cancelled attempts, " + context).containsExactlyElementsOf(monos.cancelled);
        assertThat(normalize(fluxes.registry.snapshot().toString())).as("state, " + context)
                .isEqualTo(normalize(monos.registry.snapshot().toString()));
        assertThat(fluxes.clock.pendingTasks()).as("timers, " + context).isZero();
        cover(name, fluxes);
    }

    /** One world: its calls through one twin, each subscribed once, run until nothing is left to do. */
    private static World run(Class<? extends FluxTwins.Twin> shape, Outcome[][] script,
            java.util.function.BiFunction<FluxTwins.Twin, Integer, Flux<String>> twin) {
        World world = new World();
        FluxTwins.Twin client = Resilient.of(shape.asSubclass(FluxTwins.Twin.class), shape.cast(new Backend(world, script)),
                world.registry);
        for (int call = 0; call < CALLS; call++) {
            int c = call;
            twin.apply(client, c).subscribe(value -> world.record(c, "value:" + value),
                    failure -> world.record(c, describe(failure)),
                    () -> {
                        if (world.results[c] == null) {
                            world.record(c, "value:null");
                        }
                    });
        }
        world.clock.advanceUntilIdle();
        return world;
    }

    private static void cover(String shape, World world) {
        List<String> seen = new ArrayList<>();
        if (!world.cancelled.isEmpty()) {
            seen.add("cancelled attempt");
        }
        for (String result : world.results) {
            if (result.contains("fallback:")) {
                seen.add("fallback " + result.substring(result.indexOf("fallback:") + "fallback:".length()));
            } else {
                seen.add(result.startsWith("value:") ? "value" : result.substring(0, result.indexOf(':', 6)));
            }
        }
        seen.stream().distinct().forEach(situation -> COVERAGE.merge(shape + " " + situation, 1, Integer::sum));
    }

    @AfterAll
    static void everyShapeSawFailuresSuccessesAndCancellations() {
        for (Class<?> shape : SHAPES) {
            // Without a timeout or a hedge (RetryBreakerFallback), nothing cancels an attempt.
            List<String> situations = shape == FluxTwins.RetryBreakerFallback.class ? List.of("value")
                    : List.of("value", "cancelled attempt");
            for (String situation : situations) {
                assertThat(COVERAGE.getOrDefault(shape.getSimpleName() + " " + situation, 0))
                        .as(shape.getSimpleName() + " " + situation).isGreaterThanOrEqualTo(10);
            }
        }
        // The rejections and fallbacks each shape exists for.
        for (String expected : List.of("RetryAndTimeout error:CallTimeoutException",
                "RetryBreakerFallback fallback CallNotPermittedException", "RetryBreakerFallback fallback IOException",
                "SlowCalls error:CallNotPermittedException", "FallbackTimeoutBulkhead fallback BulkheadFullException",
                "FallbackTimeoutBulkhead fallback CallTimeoutException", "LimitedRetry error:RateLimitExceededException", "Adaptive fallback LimitExceededException")) {
            assertThat(COVERAGE.getOrDefault(expected, 0)).as(expected).isGreaterThanOrEqualTo(3);
        }
    }
}
