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
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * No policy blocks a non-blocking Reactor thread on {@code Flux} methods: thousands of overlapping streams,
 * subscribed and emitting on {@code Schedulers.parallel()}, with real timers, a bulkhead queue, a rate
 * limiter waiting, timeouts that fire before and between elements and re-check while elements flow, and
 * BlockHound recording every blocking call made on those threads. Runs in the JVM of {@code blockHoundTest}.
 */
class FluxBlockHoundTest {

    @BeforeAll
    static void install() {
        BlockHounds.install();
    }

    /** The delegate: streams that complete, fail before or after elements, stall, or keep emitting past the timeout. */
    static final class Backend implements FluxBlockHoundClient {
        @Override
        public Flux<String> stack(int i) {
            Flux<String> elements = Flux.range(0, 5).map(k -> "v" + k);
            return switch (i % 5) {
                case 0 -> elements.delayElements(Duration.ofMillis(1), Schedulers.parallel());
                case 1 -> Flux.<String>error(new IllegalStateException("x")).delaySubscription(Duration.ofMillis(1),
                        Schedulers.parallel());
                case 2 -> elements.take(2).delayElements(Duration.ofMillis(1), Schedulers.parallel())
                        .concatWith(Flux.error(new IllegalStateException("broken")));
                case 3 -> Flux.just("first").concatWith(Flux.<String>never()); // an idle timeout after the commit
                // Elements 10 ms apart for 60 ms: longer than the 20 ms timeout, so its re-checks run.
                default -> Flux.range(0, 6).map(k -> "slow" + k).delayElements(Duration.ofMillis(10), Schedulers.parallel());
            };
        }

        @Override
        public Flux<String> limited(int i) {
            return Flux.just("a", "b").delayElements(Duration.ofMillis(1), Schedulers.parallel());
        }
    }

    private final FluxBlockHoundClient client = Resilient.of(FluxBlockHoundClient.class, new Backend(),
            ResilienceRegistry.builder().build());

    @Test
    void aPolicyStackNeverBlocks() {
        run(client::stack, 3_000);
    }

    @Test
    void aWaitingRateLimiterNeverBlocks() {
        run(client::limited, 1_000);
    }

    private static void run(IntFunction<Flux<String>> call, int calls) {
        BlockHounds.BLOCKING.clear();
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        Long done = Flux.range(0, calls)
                .flatMap(i -> call.apply(i).subscribeOn(Schedulers.parallel())
                        .doOnError(failures::add).onErrorResume(e -> Flux.empty()).then(), 256)
                .count()
                .block(Duration.ofMinutes(1));
        assertThat(done).isZero(); // then() emits nothing: every call ended
        assertThat(BlockHounds.BLOCKING).as("blocking calls on non-blocking threads").isEmpty();
        assertThat(failures).as("failures other than the policies' own")
                .allSatisfy(f -> assertThat(f.getClass().getPackageName()).isIn("dev.resilience4cata", "java.lang"));
    }
}
