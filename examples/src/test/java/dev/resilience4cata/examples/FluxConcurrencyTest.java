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
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Generated {@code Flux} methods under real concurrency: thousands of overlapping streams on
 * {@code Schedulers.parallel()}, with real timers, timeouts that fire before and between elements,
 * retries, fallbacks, small demand, and callers that cancel at random. What must hold every time: the
 * bulkhead never lets more streams in than its limit; each caller sees the elements of one attempt only
 * (or of the fallback), in order, with no gap and no repeat, never more than it requested, and at most
 * one end; every delegate subscription ends and is cancelled at most once; no error is dropped; and once
 * everything settled, the bulkhead, the adaptive limit and the delegate hold nothing.
 */
class FluxConcurrencyTest {

    private static final Duration SETTLE = Duration.ofSeconds(10);

    private final AtomicInteger inside = new AtomicInteger();
    private final AtomicInteger maxInside = new AtomicInteger();
    private final AtomicInteger open = new AtomicInteger();
    private final Map<Integer, AtomicInteger> cancelsPerSubscription = new ConcurrentHashMap<>();
    private final Map<Integer, AtomicInteger> attemptsPerCaller = new ConcurrentHashMap<>();
    private final AtomicInteger subscriptionIds = new AtomicInteger();
    private final List<Throwable> dropped = new CopyOnWriteArrayList<>();
    private final List<String> problems = new CopyOnWriteArrayList<>();

    @BeforeEach
    void hooks() {
        Hooks.onErrorDropped(dropped::add);
    }

    @AfterEach
    void resetHooks() {
        Hooks.resetOnErrorDropped();
    }

    /** One attempt of caller {@code i}: elements named {@code i:attempt:index}, so the caller can check where they came from. */
    private Flux<String> attempt(int i) {
        int attempt = attemptsPerCaller.computeIfAbsent(i, k -> new AtomicInteger()).getAndIncrement();
        String tag = i + ":" + attempt + ":";
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int count = 1 + random.nextInt(20);
        Flux<String> numbered = Flux.range(0, count).map(k -> tag + k);
        Flux<String> outcome = switch (random.nextInt(7)) {
            case 0 -> numbered.delayElements(Duration.ofMillis(1), Schedulers.parallel());
            case 1 -> Flux.<String>error(new IllegalStateException("x")).delaySubscription(Duration.ofMillis(1),
                    Schedulers.parallel());
            case 2 -> numbered.take(3).delayElements(Duration.ofMillis(1), Schedulers.parallel())
                    .concatWith(Flux.error(new IllegalStateException("broken")));
            case 3 -> Flux.just(tag + 0).concatWith(Flux.never());           // stalls after one: an idle timeout
            case 4 -> Flux.just(tag + 0).delaySubscription(Duration.ofMillis(40), Schedulers.parallel()); // too late
            case 5 -> Flux.never();
            default -> numbered;
        };
        return Flux.defer(() -> {
            int id = subscriptionIds.incrementAndGet();
            AtomicInteger cancels = cancelsPerSubscription.computeIfAbsent(id, k -> new AtomicInteger());
            maxInside.accumulateAndGet(inside.incrementAndGet(), Math::max);
            open.incrementAndGet();
            // Inside from the subscription to the signal that ends it, counted before that signal goes on (and so
            // before the bulkhead frees the slot); a signal racing the first one waits until it is counted.
            AtomicInteger ended = new AtomicInteger(); // 0 inside, 1 ending, 2 counted
            Runnable end = () -> {
                if (ended.compareAndSet(0, 1)) {
                    inside.decrementAndGet();
                    ended.set(2);
                } else {
                    while (ended.get() != 2) {
                        Thread.onSpinWait();
                    }
                }
            };
            return outcome
                    .doOnComplete(end)
                    .doOnError(e -> end.run())
                    .doOnCancel(() -> {
                        cancels.incrementAndGet();
                        end.run();
                    })
                    .doFinally(signal -> open.decrementAndGet());
        });
    }

    private final ResilienceRegistry registry = ResilienceRegistry.builder().build();
    private final ConcurrentFluxClient client = Resilient.of(ConcurrentFluxClient.class, this::attempt, registry);

    @RepeatedTest(5)
    void aPolicyStackKeepsItsInvariants() {
        int calls = 3_000;
        java.util.concurrent.atomic.AtomicIntegerArray ends = new java.util.concurrent.atomic.AtomicIntegerArray(calls);
        java.util.concurrent.atomic.AtomicIntegerArray cancelled = new java.util.concurrent.atomic.AtomicIntegerArray(calls);
        Flux.range(0, calls)
                .flatMap(i -> caller(i, ends, cancelled), 128)
                .then()
                .block(Duration.ofMinutes(1));

        int callersThatCancelled = 0;
        for (int i = 0; i < calls; i++) {
            assertThat(ends.get(i)).as("ends of caller %d", i).isLessThanOrEqualTo(1);
            assertThat(ends.get(i) + cancelled.get(i)).as("caller %d ended", i).isPositive();
            callersThatCancelled += ends.get(i) == 0 ? 1 : 0;
        }
        assertThat(callersThatCancelled).as("some callers did cancel").isPositive();
        assertThat(problems).as("what callers saw").isEmpty();
        awaitUntil(() -> open.get() == 0);
        assertThat(maxInside).as("streams inside the bulkhead at once").hasValueLessThanOrEqualTo(ConcurrentFluxClient.MAX_CONCURRENT);
        assertThat(cancelsPerSubscription.values()).as("no subscription cancelled twice")
                .allSatisfy(c -> assertThat(c).hasValueLessThanOrEqualTo(1));
        assertThat(dropped).as("errors dropped by Reactor").isEmpty();
        awaitUntil(() -> registry.bulkhead("cf-stack").concurrentCalls() == 0);
        assertThat(registry.bulkhead("cf-stack").waitingCalls()).isZero();
        awaitUntil(() -> registry.adaptiveLimit("cf-stack").inFlight() == 0);
    }

    /**
     * One caller: checks every element against what it requested and against the elements before it. One
     * caller in five gives up after a few milliseconds, one in seven after a few elements, one in three asks for
     * two elements at a time.
     */
    private Mono<Void> caller(int i, java.util.concurrent.atomic.AtomicIntegerArray ends,
            java.util.concurrent.atomic.AtomicIntegerArray cancelled) {
        AtomicLong requested = new AtomicLong();
        AtomicLong received = new AtomicLong();
        String[] source = new String[1];
        Flux<String> stream = client.stack(i)
                .doOnRequest(n -> requested.getAndAccumulate(n, (a, b) -> a + b < 0 ? Long.MAX_VALUE : a + b))
                .doOnNext(element -> check(i, element, source, received.incrementAndGet(), requested.get()))
                .doOnComplete(() -> ends.incrementAndGet(i))
                .doOnError(e -> ends.incrementAndGet(i))
                .doOnCancel(() -> cancelled.incrementAndGet(i))
                .onErrorResume(e -> Flux.empty());
        ThreadLocalRandom random = ThreadLocalRandom.current();
        if (i % 3 == 0) {
            stream = stream.limitRate(2);
        }
        if (i % 5 == 0) {
            stream = stream.take(Duration.ofMillis(random.nextInt(1, 20)), Schedulers.parallel());
        } else if (i % 7 == 0) {
            stream = stream.take(random.nextInt(1, 4));
        }
        return stream.subscribeOn(Schedulers.parallel()).then();
    }

    private void check(int i, String element, String[] source, long received, long requested) {
        if (received > requested) {
            problems.add("caller " + i + " got element " + received + " having requested " + requested);
        }
        String[] parts = element.split(":");
        if (Integer.parseInt(parts[0]) != i) {
            problems.add("caller " + i + " got " + element);
            return;
        }
        if (source[0] == null) {
            source[0] = parts[1];
        } else if (!source[0].equals(parts[1])) {
            problems.add("caller " + i + " got elements of attempts " + source[0] + " and " + parts[1]);
        }
        if (Long.parseLong(parts[2]) != received - 1) {
            problems.add("caller " + i + " got " + element + " as element " + received);
        }
    }

    private static void awaitUntil(BooleanSupplier condition) {
        long deadline = System.nanoTime() + SETTLE.toNanos();
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime() - deadline).as("settled in time").isNegative();
            Thread.onSpinWait();
        }
    }
}
