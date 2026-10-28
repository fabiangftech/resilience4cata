package dev.resilience4cata.examples;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Generated {@code Mono} methods under real concurrency: thousands of overlapping calls on
 * {@code Schedulers.parallel()}, with real timers, timeouts and hedges that fire, retries, and callers
 * that cancel at random. What must hold every time: the bulkhead never lets more calls in than its limit,
 * every delegate subscription ends (completed, failed or cancelled) and is cancelled at most once,
 * every caller gets at most one outcome and ends, no error is dropped (and a value only when its caller
 * cancelled, which Reactor's {@code take} does when its cancel races the value), and once everything
 * settled the bulkhead, the adaptive limit and the delegate hold nothing.
 */
class MonoConcurrencyTest {

    private static final Duration SETTLE = Duration.ofSeconds(10);

    private final AtomicInteger inside = new AtomicInteger();
    private final AtomicInteger maxInside = new AtomicInteger();
    private final AtomicInteger open = new AtomicInteger();
    private final Map<Integer, AtomicInteger> cancelsPerSubscription = new ConcurrentHashMap<>();
    private final AtomicInteger subscriptionIds = new AtomicInteger();
    private final List<Throwable> dropped = new CopyOnWriteArrayList<>();
    private final List<Object> droppedValues = new CopyOnWriteArrayList<>();

    @BeforeEach
    void hooks() {
        Hooks.onErrorDropped(dropped::add);
        Hooks.onNextDropped(droppedValues::add);
    }

    @AfterEach
    void resetHooks() {
        Hooks.resetOnErrorDropped();
        Hooks.resetOnNextDropped();
    }

    /** Each attempt: a value soon, a failure, a value too late for the timeout, or one at once. */
    private Mono<String> attempt(int i) {
        Mono<String> outcome = switch (ThreadLocalRandom.current().nextInt(5)) {
            case 0 -> Mono.just("v:" + i).delayElement(Duration.ofMillis(1 + i % 3), Schedulers.parallel());
            case 1 -> Mono.<String>error(new IllegalStateException("x")).delaySubscription(Duration.ofMillis(1),
                    Schedulers.parallel());
            case 2 -> Mono.just("late:" + i).delayElement(Duration.ofMillis(50), Schedulers.parallel());
            case 3 -> Mono.never();
            default -> Mono.just("now:" + i);
        };
        return Mono.defer(() -> {
            int id = subscriptionIds.incrementAndGet();
            AtomicInteger cancels = cancelsPerSubscription.computeIfAbsent(id, k -> new AtomicInteger());
            int now = inside.incrementAndGet();
            maxInside.accumulateAndGet(now, Math::max);
            open.incrementAndGet();
            // Inside from the subscription to the first signal that ends it, seen before it goes downstream (and
            // so before the policies react to it): a value, an error, or the cancel.
            java.util.concurrent.atomic.AtomicBoolean ended = new java.util.concurrent.atomic.AtomicBoolean();
            Runnable end = () -> {
                if (ended.compareAndSet(false, true)) {
                    inside.decrementAndGet();
                }
            };
            return outcome
                    .doOnSuccess(v -> end.run())
                    .doOnError(e -> end.run())
                    .doOnCancel(() -> {
                        cancels.incrementAndGet();
                        end.run();
                    })
                    .doFinally(signal -> open.decrementAndGet());
        });
    }

    private final ResilienceRegistry registry = ResilienceRegistry.builder().build();
    private final ConcurrentMonoClient client = Resilient.of(ConcurrentMonoClient.class, new ConcurrentMonoClient() {
        @Override
        public Mono<String> stack(int i) {
            return attempt(i);
        }

        @Override
        public Mono<String> hedged(int i) {
            return attempt(i);
        }
    }, registry);

    @RepeatedTest(5)
    void aPolicyStackKeepsItsInvariants() {
        run(client::stack, 3_000, ConcurrentMonoClient.MAX_CONCURRENT);
        assertThat(registry.bulkhead("cc-stack").concurrentCalls()).isZero();
        assertThat(registry.bulkhead("cc-stack").waitingCalls()).isZero();
        assertThat(registry.adaptiveLimit("cc-stack").inFlight()).isZero();
    }

    @RepeatedTest(5)
    void hedgesKeepTheirInvariants() {
        run(client::hedged, 2_000, ConcurrentMonoClient.MAX_CONCURRENT * 3);
        assertThat(registry.bulkhead("cc-hedge").concurrentCalls()).isZero();
    }

    private void run(IntFunction<Mono<String>> call, int calls, int limit) {
        java.util.concurrent.atomic.AtomicIntegerArray outcomes = new java.util.concurrent.atomic.AtomicIntegerArray(calls);
        java.util.concurrent.atomic.AtomicIntegerArray cancelled = new java.util.concurrent.atomic.AtomicIntegerArray(calls);
        Flux.range(0, calls)
                .flatMap(i -> {
                    Mono<String> mono = call.apply(i).subscribeOn(Schedulers.parallel())
                            .doOnSuccess(v -> outcomes.incrementAndGet(i))
                            .doOnError(e -> outcomes.incrementAndGet(i))
                            .onErrorResume(e -> Mono.empty());
                    // One caller in five gives up after a random few milliseconds: take cancels the call. Reactor lets
                    // that cancel race the outcome, so such a caller may see both.
                    return i % 5 == 0
                            ? mono.doOnCancel(() -> cancelled.incrementAndGet(i))
                                    .take(Duration.ofMillis(ThreadLocalRandom.current().nextInt(1, 20)), Schedulers.parallel())
                            : mono;
                }, 128)
                .then()
                .block(Duration.ofMinutes(1));

        int callersThatCancelled = 0;
        for (int i = 0; i < calls; i++) {
            assertThat(outcomes.get(i)).as("outcomes of caller %d", i).isLessThanOrEqualTo(1);
            assertThat(outcomes.get(i) + cancelled.get(i)).as("caller %d ended", i).isPositive();
            callersThatCancelled += outcomes.get(i) == 0 ? 1 : 0;
        }
        assertThat(callersThatCancelled).as("some callers did cancel").isPositive();
        awaitUntil(() -> open.get() == 0);
        assertThat(maxInside).as("calls inside the bulkhead at once").hasValueLessThanOrEqualTo(limit);
        assertThat(cancelsPerSubscription.values()).as("no subscription cancelled twice")
                .allSatisfy(c -> assertThat(c).hasValueLessThanOrEqualTo(1));
        assertThat(dropped).as("errors dropped by Reactor").isEmpty();
        // Reactor's take drops a value that races its own cancel: only a caller that cancelled may lose one.
        assertThat(droppedValues).as("values dropped by Reactor").allSatisfy(value -> {
            int caller = Integer.parseInt(value.toString().substring(value.toString().indexOf(':') + 1));
            assertThat(cancelled.get(caller)).as("caller %d cancelled", caller).isPositive();
        });
    }

    private static void awaitUntil(BooleanSupplier condition) {
        long deadline = System.nanoTime() + SETTLE.toNanos();
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime() - deadline).as("settled in time").isNegative();
            Thread.onSpinWait();
        }
    }
}
