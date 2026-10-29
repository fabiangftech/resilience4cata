package dev.resilience4cata.examples;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.IntFunction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reactor.blockhound.BlockHound;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * No policy blocks a non-blocking Reactor thread: thousands of overlapping calls of generated
 * {@code Mono} methods, subscribed and completed on {@code Schedulers.parallel()}, with real timers,
 * real contention (a bulkhead queue, a rate limiter waiting, hedges, timeouts that fire), and BlockHound
 * recording every blocking call made on those threads. Runs in its own JVM ({@code blockHoundTest}):
 * BlockHound instruments the JVM for good.
 */
class MonoBlockHoundTest {

    private static final List<String> BLOCKING = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void install() {
        // install(...), not builder().install(): only install loads the integrations on the class path, Reactor's
        // among them, which marks the Reactor schedulers' threads as non-blocking.
        BlockHound.install(builder -> builder
                // Cancelling a timer task (a bulkhead waiter's, a retry's backoff) removes it from the shared
                // ScheduledThreadPoolExecutor's queue under its lock: the same short lock as scheduling (offer) and
                // the timer thread's take, which Reactor's own BlockHound integration allows for its schedulers.
                .allowBlockingCallsInside("java.util.concurrent.ScheduledThreadPoolExecutor$DelayedWorkQueue", "remove")
                .blockingMethodCallback(method -> BLOCKING.add(method + " on " + Thread.currentThread().getName()
                        + "\n" + stack())));
    }

    private static String stack() {
        StringBuilder text = new StringBuilder();
        for (StackTraceElement frame : new Throwable().getStackTrace()) {
            text.append("    at ").append(frame).append('\n');
        }
        return text.toString();
    }

    /** The delegate: a value, a failure, or a value too late for the timeout, emitted on the parallel scheduler. */
    static final class Backend implements BlockHoundClient {
        @Override
        public Mono<String> stack(int i) {
            return outcome(i);
        }

        @Override
        public Mono<String> hedged(int i) {
            return outcome(i);
        }

        @Override
        public Mono<String> limited(int i) {
            return Mono.just("v" + i).delayElement(Duration.ofMillis(1), Schedulers.parallel());
        }

        private static Mono<String> outcome(int i) {
            return switch (i % 4) {
                case 0 -> Mono.just("v" + i).delayElement(Duration.ofMillis(1), Schedulers.parallel());
                case 1 -> Mono.<String>error(new IllegalStateException("x")).delaySubscription(Duration.ofMillis(1),
                        Schedulers.parallel());
                case 2 -> Mono.just("late").delayElement(Duration.ofMillis(60), Schedulers.parallel());
                default -> Mono.just("now");
            };
        }
    }

    private final BlockHoundClient client = Resilient.of(BlockHoundClient.class, new Backend(),
            ResilienceRegistry.builder().build());

    @Test
    void blockHoundSeesBlockingCallsOnParallelThreads() {
        Mono<Object> blocking = Mono.fromCallable(() -> {
            Thread.sleep(1);
            return "slept";
        }).subscribeOn(Schedulers.parallel()).cast(Object.class);
        int before = BLOCKING.size();
        blocking.block(Duration.ofSeconds(5));
        assertThat(BLOCKING).as("BlockHound is installed and reports").hasSizeGreaterThan(before);
        BLOCKING.clear();
    }

    @Test
    void aPolicyStackNeverBlocks() {
        run(client::stack, 4_000);
    }

    @Test
    void hedgesNeverBlock() {
        run(client::hedged, 2_000);
    }

    @Test
    void aWaitingRateLimiterNeverBlocks() {
        run(client::limited, 1_000);
    }

    private static void run(IntFunction<Mono<String>> call, int calls) {
        BLOCKING.clear();
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        Long done = Flux.range(0, calls)
                .flatMap(i -> call.apply(i).subscribeOn(Schedulers.parallel())
                        .doOnError(failures::add).onErrorResume(e -> Mono.just("failed")), 256)
                .count()
                .block(Duration.ofMinutes(1));
        assertThat(done).isEqualTo(calls);
        assertThat(BLOCKING).as("blocking calls on non-blocking threads").isEmpty();
        assertThat(failures).as("failures other than the policies' own")
                .allSatisfy(f -> assertThat(f.getClass().getPackageName()).isIn("dev.resilience4cata", "java.lang"));
    }
}
