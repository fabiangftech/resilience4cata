package dev.resilience4cata.examples;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.BulkheadFullException;
import dev.resilience4cata.CallNotPermittedException;
import dev.resilience4cata.CallTimeoutException;
import dev.resilience4cata.CircuitBreakerState;
import dev.resilience4cata.AdaptiveLimitFullException;
import dev.resilience4cata.RateLimitExceededException;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import dev.resilience4cata.event.FallbackEvent;
import dev.resilience4cata.event.ResilienceEvent;
import dev.resilience4cata.event.RetryEvent;
import dev.resilience4cata.event.TimeoutEvent;
import dev.resilience4cata.testkit.ManualClock;
import dev.resilience4cata.testkit.ResilienceTesting;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

/**
 * Generated {@code Flux} methods end to end: {@link FluxClient} through {@link Resilient#of} and
 * {@link FluxService} through {@link Resilient#create}, on a {@link ManualClock}, so that every element,
 * delay and timeout happens when the test advances the clock.
 */
class FluxEndToEndTest {

    private static final Duration VERIFY = Duration.ofSeconds(5);

    private final ManualClock clock = new ManualClock();
    private final List<ResilienceEvent> events = new CopyOnWriteArrayList<>();
    private final ResilienceRegistry registry = ResilienceTesting.registry(clock)
            .listen(ResilienceEvent.class, events::add).build();
    private final Backend backend = new Backend();
    private final FluxClient client = Resilient.of(FluxClient.class, backend, registry);
    private final List<Throwable> droppedErrors = new CopyOnWriteArrayList<>();

    FluxEndToEndTest() {
        Hooks.onErrorDropped(droppedErrors::add);
    }

    @AfterEach
    void nothingWasDroppedAndNothingIsLeft() {
        Hooks.resetOnErrorDropped();
        assertThat(droppedErrors).as("errors dropped by Reactor").isEmpty();
    }

    /**
     * The delegate: each method returns, call after call, the streams scripted for it (the last one again once
     * the script runs out), and counts its calls, subscriptions and cancellations.
     */
    final class Backend implements FluxClient {
        final Map<String, Deque<Supplier<Flux<?>>>> scripts = new ConcurrentHashMap<>();
        final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
        final Map<String, AtomicInteger> cancellations = new ConcurrentHashMap<>();
        final Map<String, List<Long>> requests = new ConcurrentHashMap<>();

        @SafeVarargs
        final void script(String method, Supplier<Flux<?>>... attempts) {
            Deque<Supplier<Flux<?>>> script = new ArrayDeque<>();
            for (Supplier<Flux<?>> attempt : attempts) {
                script.add(attempt);
            }
            scripts.put(method, script);
        }

        int calls(String method) {
            return calls.computeIfAbsent(method, m -> new AtomicInteger()).get();
        }

        int cancellations(String method) {
            return cancellations.computeIfAbsent(method, m -> new AtomicInteger()).get();
        }

        List<Long> requests(String method) {
            return requests.computeIfAbsent(method, m -> new CopyOnWriteArrayList<>());
        }

        @SuppressWarnings("unchecked")
        private <T> Flux<T> next(String method) {
            calls.computeIfAbsent(method, m -> new AtomicInteger()).incrementAndGet();
            Deque<Supplier<Flux<?>>> script = scripts.get(method);
            Supplier<Flux<?>> attempt = script.size() > 1 ? script.poll() : script.peek();
            return ((Flux<T>) attempt.get())
                    .doOnRequest(requests(method)::add)
                    .doOnCancel(() -> cancellations.computeIfAbsent(method, m -> new AtomicInteger()).incrementAndGet());
        }

        @Override public Flux<String> retried(String id) { return next("retried"); }
        @Override public Flux<String> guarded(String id) { return next("guarded"); }
        @Override public Flux<String> slow(String id) { return next("slow"); }
        @Override public Flux<String> timed(String id) { return next("timed"); }
        @Override public Flux<String> bulkheaded(String id) { return next("bulkheaded"); }
        @Override public Flux<String> bulkheadedWithWait(String id) { return next("bulkheadedWithWait"); }
        @Override public Flux<String> limited(String id) { return next("limited"); }
        @Override public Flux<String> adaptive(String id) { return next("adaptive"); }
        @Override public Flux<String> recovered(String id) { return next("recovered"); }
        @Override public Flux<String> recoveredWithAValue(String id) { return next("recoveredWithAValue"); }
        @Override public Flux<String> handled(String id) { return next("handled"); }
        @Override public Flux<String> stack(String id) { return next("stack"); }
        @Override public Flux<? extends CharSequence> wildcard(String id) { return next("wildcard"); }
        @Override public <T> Flux<java.util.List<T>> generic(Class<T> type) { return next("generic"); }
        @Override public Flux<String> plain(String id) { return next("plain"); }
    }

    private static Supplier<Flux<?>> elements(String... elements) {
        return () -> Flux.just(elements);
    }

    private static Supplier<Flux<?>> failure(String message) {
        return () -> Flux.error(new IOException(message));
    }

    private static Supplier<Flux<?>> brokenAfter(String... elements) {
        return () -> Flux.just(elements).concatWith(Flux.error(new IOException("broken")));
    }

    /** A stream on the manual clock: {@code "a@500"} emits {@code a} 500 ms after subscribing, {@code ".@900"} ends it. */
    private Supplier<Flux<?>> onClock(String... elements) {
        return () -> Flux.defer(() -> {
            Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
            for (String element : elements) {
                String[] parts = element.split("@");
                Runnable emit = switch (parts[0]) {
                    case "." -> sink::tryEmitComplete;
                    case "!" -> () -> sink.tryEmitError(new IOException("broken later"));
                    default -> () -> sink.tryEmitNext(parts[0]);
                };
                clock.schedule(Duration.ofMillis(Long.parseLong(parts[1])).toNanos(), emit);
            }
            return sink.asFlux();
        });
    }

    private long count(Class<? extends ResilienceEvent> type) {
        return events.stream().filter(type::isInstance).count();
    }

    // ---- subscriptions, demand and context

    @Test
    void nothingRunsBeforeASubscriptionAndEachSubscriptionIsOneCall() {
        backend.script("retried", elements("a", "b"));
        Flux<String> flux = client.retried("x");
        assertThat(backend.calls("retried")).isZero();
        StepVerifier.create(flux).expectNext("a", "b").expectComplete().verify(VERIFY);
        StepVerifier.create(flux).expectNext("a", "b").expectComplete().verify(VERIFY);
        assertThat(backend.calls("retried")).isEqualTo(2);
    }

    @Test
    void elementsFollowTheSubscribersDemandAcrossAttempts() {
        backend.script("retried", failure("connect"), () -> Flux.range(1, 100).map(String::valueOf));
        StepVerifier.create(client.retried("x"), 2)
                .then(() -> clock.advance(Duration.ofMillis(100)))
                .expectNext("1", "2")
                .expectNoEvent(Duration.ofMillis(10))
                .thenRequest(3).expectNext("3", "4", "5")
                .thenCancel().verify(VERIFY);
        assertThat(backend.requests("retried")).as("the second attempt is asked for what was requested")
                .containsExactly(2L, 2L, 3L);
    }

    @Test
    void theSubscribersContextReachesEveryAttemptAndTheFallback() {
        List<String> seen = new CopyOnWriteArrayList<>();
        Supplier<Flux<?>> failing = () -> Flux.deferContextual(context -> {
            seen.add(context.get("user"));
            return Flux.error(new IOException("down"));
        });
        backend.script("recovered", failing);
        StepVerifier.create(client.recovered("x").contextWrite(Context.of("user", "ana")))
                .then(() -> clock.advance(Duration.ofMillis(100)))
                .expectNext("recovered x", "from down", "for ana").expectComplete().verify(VERIFY);
        assertThat(seen).containsExactly("ana", "ana");
    }

    // ---- retry and fallback act before the first element only

    @Test
    void failuresBeforeTheFirstElementAreRetried() {
        backend.script("retried", failure("1"), failure("2"), elements("a", "b", "c"));
        StepVerifier.create(client.retried("x"))
                .then(() -> clock.advance(Duration.ofMillis(100)))
                .then(() -> clock.advance(Duration.ofMillis(100)))
                .expectNext("a", "b", "c").expectComplete().verify(VERIFY);
        assertThat(backend.calls("retried")).isEqualTo(3);
        assertThat(count(RetryEvent.Retrying.class)).isEqualTo(2);
    }

    @Test
    void aFailureAfterTheFirstElementReachesTheSubscriberAsItIs() {
        backend.script("retried", brokenAfter("a", "b"));
        StepVerifier.create(client.retried("x")).expectNext("a", "b").expectErrorMessage("broken").verify(VERIFY);
        assertThat(backend.calls("retried")).isOne();
        assertThat(events).noneMatch(RetryEvent.class::isInstance);
        assertThat(clock.pendingTasks()).isZero();
    }

    @Test
    void theFallbackReplacesAStreamThatNeverStarted() {
        backend.script("recovered", failure("down"));
        StepVerifier.create(client.recovered("x"))
                .then(() -> clock.advance(Duration.ofMillis(100)))
                .expectNext("recovered x", "from down", "for nobody").expectComplete().verify(VERIFY);
        assertThat(count(FallbackEvent.Applied.class)).isOne();
    }

    @Test
    void theFallbackDoesNotRunAfterTheFirstElement() {
        backend.script("recovered", brokenAfter("a"));
        StepVerifier.create(client.recovered("x")).expectNext("a").expectErrorMessage("broken").verify(VERIFY);
        assertThat(events).noneMatch(FallbackEvent.class::isInstance);
    }

    @Test
    void aFallbackValueIsAStreamOfOneElement() {
        backend.script("recoveredWithAValue", onClock("late@500"));
        StepVerifier.create(client.recoveredWithAValue("x"))
                .then(() -> clock.advance(Duration.ofMillis(200)))
                .expectNext("placeholder x").expectComplete().verify(VERIFY);
        assertThat(backend.cancellations("recoveredWithAValue")).isOne();
        clock.advanceUntilIdle();
    }

    @Test
    void aFallbackHandlerSeesTheAttemptsAndItsValueIsOneElement() {
        backend.script("handled", onClock("late@500"));
        StepVerifier.create(client.handled("x"))
                .then(() -> clock.advance(Duration.ofMillis(200)))
                .expectNext("cached x after 1").expectComplete().verify(VERIFY);
        clock.advanceUntilIdle();
    }

    // ---- the timeout restarts on every element

    @Test
    void aStreamThatKeepsEmittingNeverTimesOut() {
        backend.script("timed", onClock("a@400", "b@800", "c@1200", "d@1600", ".@2000"));
        StepVerifier.create(client.timed("x"))
                .then(() -> clock.advance(Duration.ofMillis(2000)))
                .expectNext("a", "b", "c", "d").expectComplete().verify(VERIFY);
        assertThat(events).noneMatch(TimeoutEvent.class::isInstance);
        assertThat(clock.pendingTasks()).isZero();
    }

    @Test
    void aSilenceBetweenElementsTimesOutAndCancelsTheStream() {
        backend.script("timed", onClock("a@100", "b@200", "c@2000"));
        StepVerifier.create(client.timed("x"))
                .then(() -> clock.advance(Duration.ofMillis(200)))
                .expectNext("a", "b")
                .then(() -> clock.advance(Duration.ofMillis(499)))
                .expectNoEvent(Duration.ofMillis(10))
                .then(() -> clock.advance(Duration.ofMillis(1)))
                .expectError(CallTimeoutException.class).verify(VERIFY);
        assertThat(backend.cancellations("timed")).isOne();
        assertThat(count(TimeoutEvent.TimedOut.class)).isOne();
        clock.advanceUntilIdle();
        assertThat(clock.pendingTasks()).isZero();
    }

    @Test
    void aSlowFirstElementTimesOut() {
        backend.script("timed", onClock("a@600"));
        StepVerifier.create(client.timed("x"))
                .then(() -> clock.advance(Duration.ofMillis(500)))
                .expectError(CallTimeoutException.class).verify(VERIFY);
        clock.advanceUntilIdle();
    }

    // ---- breaker

    @Test
    void theBreakerRecordsFailuresAfterElementsAndOpens() {
        backend.script("guarded", brokenAfter("a"));
        StepVerifier.create(client.guarded("x")).expectNext("a").expectError(IOException.class).verify(VERIFY);
        StepVerifier.create(client.guarded("x")).expectNext("a").expectError(IOException.class).verify(VERIFY);
        assertThat(registry.circuitBreaker("flux-breaker").state()).isEqualTo(CircuitBreakerState.OPEN);
        StepVerifier.create(client.guarded("x")).expectError(CallNotPermittedException.class).verify(VERIFY);
        assertThat(backend.calls("guarded")).as("rejected without calling").isEqualTo(2);
    }

    @Test
    void aSlowCallIsOneWhoseFirstElementIsSlowNotALongStream() {
        backend.script("slow", onClock("a@100", "b@5000", ".@6000"), onClock("a@1500", ".@1600"));
        StepVerifier.create(client.slow("x")).then(() -> clock.advance(Duration.ofSeconds(6)))
                .expectNext("a", "b").expectComplete().verify(VERIFY);
        assertThat(registry.circuitBreaker("flux-slow").snapshot().slowCalls()).as("6 s long, first element at 100 ms")
                .isZero();
        StepVerifier.create(client.slow("x")).then(() -> clock.advance(Duration.ofMillis(1600)))
                .expectNext("a").expectComplete().verify(VERIFY);
        assertThat(registry.circuitBreaker("flux-slow").snapshot().slowCalls()).isOne();
    }

    // ---- bulkhead, rate limiter, adaptive limit: held for the whole stream

    @Test
    void theBulkheadHoldsItsSlotForTheStreamAndACancelFreesIt() {
        backend.script("bulkheaded", onClock("a@100", "b@5000", ".@6000"));
        List<String> seen = new CopyOnWriteArrayList<>();
        Disposable first = client.bulkheaded("x").subscribe(seen::add);
        clock.advance(Duration.ofMillis(100));
        assertThat(seen).containsExactly("a");
        StepVerifier.create(client.bulkheaded("y")).expectError(BulkheadFullException.class).verify(VERIFY);
        first.dispose();
        assertThat(registry.bulkhead("flux-bulkhead").concurrentCalls()).isZero();
        assertThat(backend.cancellations("bulkheaded")).isOne();
        clock.advanceUntilIdle();
    }

    @Test
    void aWaitingStreamStartsWhenTheFirstOneEnds() {
        backend.script("bulkheadedWithWait", onClock("a@100", ".@1000"), onClock("b@100", ".@200"));
        List<String> seen = new CopyOnWriteArrayList<>();
        client.bulkheadedWithWait("x").subscribe(seen::add);
        client.bulkheadedWithWait("y").subscribe(seen::add);
        assertThat(registry.bulkhead("flux-bulkhead-wait").waitingCalls()).isOne();
        clock.advance(Duration.ofMillis(1000));
        // The hand-off subscribes the second stream on a virtual thread, and that stream puts its elements on the clock
        // when it is subscribed: wait for its request, which comes after that, before advancing the clock again.
        MonoEndToEndTest.Awaiting.until(() -> backend.requests("bulkheadedWithWait").size() == 2);
        clock.advance(Duration.ofMillis(200));
        MonoEndToEndTest.Awaiting.until(() -> seen.size() == 2);
        assertThat(seen).containsExactly("a", "b");
    }

    @Test
    void theRateLimiterTakesOnePermitPerSubscription() {
        backend.script("limited", elements("a", "b", "c"));
        StepVerifier.create(client.limited("x")).expectNext("a", "b", "c").expectComplete().verify(VERIFY);
        StepVerifier.create(client.limited("x")).expectError(RateLimitExceededException.class).verify(VERIFY);
    }

    @Test
    void theAdaptiveLimitHoldsItsSlotWhileTheStreamRuns() {
        backend.script("adaptive", onClock("a@100", ".@3000"));
        client.adaptive("x").subscribe();
        clock.advance(Duration.ofMillis(100));
        StepVerifier.create(client.adaptive("y")).expectError(AdaptiveLimitFullException.class).verify(VERIFY);
        clock.advance(Duration.ofMillis(2900));
        assertThat(registry.adaptiveLimit("flux-adaptive").concurrentCalls()).isZero();
    }

    // ---- a stack, odd types, delegation, an annotated class

    @Test
    void aStackRetriesATimeoutBeforeTheFirstElementAndFallsBackAtTheEnd() {
        backend.script("stack", onClock("late@400"), failure("down"), failure("still down"));
        StepVerifier.create(client.stack("x"))
                .then(() -> clock.advance(Duration.ofMillis(300)))  // the first attempt times out
                .then(() -> clock.advance(Duration.ofMillis(100)))  // the second fails at once
                .then(() -> clock.advance(Duration.ofMillis(100)))  // the third too: the fallback
                .expectNext("recovered x", "from still down", "for nobody").expectComplete().verify(VERIFY);
        assertThat(backend.calls("stack")).isEqualTo(3);
        assertThat(registry.bulkhead("flux-stack").concurrentCalls()).isZero();
        clock.advanceUntilIdle();
        assertThat(clock.pendingTasks()).isZero();
    }

    @Test
    void wildcardAndGenericFluxesWork() {
        backend.script("wildcard", failure("x"), elements("a"));
        StepVerifier.create(client.wildcard("x").map(CharSequence::toString)).then(() -> clock.advance(Duration.ofMillis(100)))
                .expectNext("a").expectComplete().verify(VERIFY);
        backend.script("generic", () -> Flux.just(List.of(1, 2)));
        StepVerifier.create(client.generic(Integer.class)).expectNext(List.of(1, 2)).expectComplete().verify(VERIFY);
    }

    @Test
    void anUnannotatedFluxMethodIsDelegatedAsItIs() {
        Flux<?> same = Flux.just("p");
        backend.script("plain", () -> same);
        assertThat(client.plain("x").collectList().block(VERIFY)).containsExactly("p");
        assertThat(backend.requests("plain")).as("no policy in between").containsExactly(Long.MAX_VALUE);
    }

    @Test
    void anAnnotatedClassRetriesItsFluxMethodsAndInterceptsSelfCalls() {
        FluxService service = Resilient.create(FluxService.class, registry);
        service.failures = 2;
        StepVerifier.create(service.pages("p"))
                .then(() -> clock.advance(Duration.ofMillis(200)))
                .expectNext("p-1", "p-2").expectComplete().verify(VERIFY);
        assertThat(service.calls).hasValue(3);

        service.failures = 10;
        StepVerifier.create(service.firstPages("q"))
                .then(() -> clock.advance(Duration.ofMillis(200)))
                .expectNext("no pages for q").expectComplete().verify(VERIFY);
    }
}
