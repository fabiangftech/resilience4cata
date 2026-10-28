package dev.resilience4cata.examples;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.BulkheadFullException;
import dev.resilience4cata.CallNotPermittedException;
import dev.resilience4cata.CallTimeoutException;
import dev.resilience4cata.CircuitBreakerState;
import dev.resilience4cata.LimitExceededException;
import dev.resilience4cata.RateLimitExceededException;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import dev.resilience4cata.ResultRejectedException;
import dev.resilience4cata.event.FallbackEvent;
import dev.resilience4cata.event.HedgeEvent;
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
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

/**
 * Generated {@code Mono} methods end to end: {@link MonoClient} through {@link Resilient#of}, on a
 * {@link ManualClock}, so every delay, timeout and hedge runs when the test advances the clock.
 */
class MonoEndToEndTest {

    private static final Duration VERIFY = Duration.ofSeconds(5);

    private final ManualClock clock = new ManualClock();
    private final List<ResilienceEvent> events = new CopyOnWriteArrayList<>();
    private final ResilienceRegistry registry = ResilienceTesting.registry(clock)
            .listen(ResilienceEvent.class, events::add).build();
    private final Backend backend = new Backend();
    private final MonoClient client = Resilient.of(MonoClient.class, backend, registry);
    private final List<Throwable> droppedErrors = new CopyOnWriteArrayList<>();

    MonoEndToEndTest() {
        Hooks.onErrorDropped(droppedErrors::add);
    }

    @AfterEach
    void nothingWasDropped() {
        Hooks.resetOnErrorDropped();
        assertThat(droppedErrors).as("errors dropped by Reactor").isEmpty();
    }

    /**
     * The delegate: each method returns, call after call, the Monos scripted for it (the last one again
     * once the script runs out), and counts its calls, subscriptions and cancellations.
     */
    static final class Backend implements MonoClient {
        final Map<String, Deque<Supplier<Mono<?>>>> scripts = new ConcurrentHashMap<>();
        final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
        final Map<String, AtomicInteger> subscriptions = new ConcurrentHashMap<>();
        final Map<String, AtomicInteger> cancellations = new ConcurrentHashMap<>();
        IOException thrownOnce;

        @SafeVarargs
        final void script(String method, Supplier<Mono<?>>... attempts) {
            Deque<Supplier<Mono<?>>> script = new ArrayDeque<>();
            for (Supplier<Mono<?>> attempt : attempts) {
                script.add(attempt);
            }
            scripts.put(method, script);
        }

        int calls(String method) {
            return calls.computeIfAbsent(method, m -> new AtomicInteger()).get();
        }

        int subscriptions(String method) {
            return subscriptions.computeIfAbsent(method, m -> new AtomicInteger()).get();
        }

        int cancellations(String method) {
            return cancellations.computeIfAbsent(method, m -> new AtomicInteger()).get();
        }

        @SuppressWarnings("unchecked")
        private <T> Mono<T> next(String method) {
            calls.computeIfAbsent(method, m -> new AtomicInteger()).incrementAndGet();
            Deque<Supplier<Mono<?>>> script = scripts.get(method);
            Supplier<Mono<?>> attempt = script.size() > 1 ? script.poll() : script.peek();
            return ((Mono<T>) attempt.get())
                    .doOnSubscribe(s -> subscriptions.computeIfAbsent(method, m -> new AtomicInteger()).incrementAndGet())
                    .doOnCancel(() -> cancellations.computeIfAbsent(method, m -> new AtomicInteger()).incrementAndGet());
        }

        @Override public Mono<String> retried(String id) { return next("retried"); }

        @Override
        public Mono<String> retriedOnIo(String id) throws IOException {
            if (thrownOnce != null) {
                IOException failure = thrownOnce;
                thrownOnce = null;
                calls.computeIfAbsent("retriedOnIo", m -> new AtomicInteger()).incrementAndGet();
                throw failure;
            }
            return next("retriedOnIo");
        }

        @Override public Mono<String> guarded(String id) { return next("guarded"); }
        @Override public Mono<String> timed(String id) { return next("timed"); }
        @Override public Mono<String> bulkheaded(String id) { return next("bulkheaded"); }
        @Override public Mono<String> bulkheadedWithWait(String id) { return next("bulkheadedWithWait"); }
        @Override public Mono<String> limited(String id) { return next("limited"); }
        @Override public Mono<String> limitedWithWait(String id) { return next("limitedWithWait"); }
        @Override public Mono<String> adaptive(String id) { return next("adaptive"); }
        @Override public Mono<String> hedged(String id) { return next("hedged"); }
        @Override public Mono<String> recovered(String id) { return next("recovered"); }
        @Override public Mono<String> handled(String id) { return next("handled"); }
        @Override public Mono<String> classified(String id) { return next("classified"); }
        @Override public Mono<String> stack(String id) { return next("stack"); }
        @Override public Mono<String> oneTimeoutForAllAttempts(String id) { return next("oneTimeoutForAllAttempts"); }
        @Override public Mono<Void> send(String message) { return next("send"); }
        @Override public Mono<? extends CharSequence> wildcard(String id) { return next("wildcard"); }
        @Override public <T> Mono<List<T>> generic(Class<T> type) { return next("generic"); }
        @Override public Mono<String> plain(String id) { return next("plain"); }
    }

    private static Supplier<Mono<?>> value(String value) {
        return () -> Mono.just(value);
    }

    private static Supplier<Mono<?>> failure(String message) {
        return () -> Mono.error(new IOException(message));
    }

    private static Supplier<Mono<?>> never() {
        return Mono::never;
    }

    private static Supplier<Mono<?>> from(Sinks.One<String> sink) {
        return sink::asMono;
    }

    private void advance(long millis) {
        clock.advance(Duration.ofMillis(millis));
    }

    private <T> List<T> events(Class<T> type) {
        return events.stream().filter(type::isInstance).map(type::cast).toList();
    }

    // ---- retry

    @Test
    void retriesUntilASuccessAndRunsNothingBeforeASubscription() {
        backend.script("retried", failure("one"), failure("two"), value("three"));

        Mono<String> mono = client.retried("a");
        assertThat(backend.calls("retried")).as("lazy").isZero();
        StepVerifier.create(mono).then(() -> advance(100)).then(() -> advance(100))
                .expectNext("three").expectComplete().verify(VERIFY);
        assertThat(backend.calls("retried")).isEqualTo(3);
        assertThat(backend.subscriptions("retried")).isEqualTo(3);
        assertThat(events(RetryEvent.Retrying.class)).hasSize(2);
    }

    @Test
    void everySubscriptionIsANewCall() {
        backend.script("retried", value("v"));
        Mono<String> mono = client.retried("a");
        for (int i = 0; i < 3; i++) {
            StepVerifier.create(mono).expectNext("v").expectComplete().verify(VERIFY);
        }
        assertThat(backend.calls("retried")).isEqualTo(3);
    }

    @Test
    void givesUpWithTheLastFailure() {
        backend.script("retried", failure("one"), failure("two"), failure("three"));
        StepVerifier.create(client.retried("a")).then(() -> advance(100)).then(() -> advance(100))
                .expectErrorMessage("three").verify(VERIFY);
        assertThat(events(RetryEvent.GaveUp.class)).hasSize(1);
    }

    @Test
    void retryOnDecidesAndAThrowBeforeTheMonoIsAnAttempt() throws IOException {
        backend.thrownOnce = new IOException("assembly");
        backend.script("retriedOnIo", value("after the throw"));
        StepVerifier.create(client.retriedOnIo("a")).then(() -> advance(100))
                .expectNext("after the throw").expectComplete().verify(VERIFY);

        backend.script("retriedOnIo", () -> Mono.error(new IllegalStateException("not retried")), value("never"));
        StepVerifier.create(client.retriedOnIo("a")).expectError(IllegalStateException.class).verify(VERIFY);
        assertThat(backend.calls("retriedOnIo")).isEqualTo(3);
    }

    @Test
    void cancellingDuringTheBackoffStopsTheRetry() {
        backend.script("retried", failure("one"), value("never"));
        StepVerifier.create(client.retried("a")).then(() -> assertThat(clock.pendingTasks()).isOne())
                .thenCancel().verify(VERIFY);
        clock.advanceUntilIdle();
        assertThat(backend.calls("retried")).isOne();
        assertThat(events(RetryEvent.GaveUp.class)).as("a cancel is not a give-up").isEmpty();
    }

    @Test
    void theContextReachesEveryAttempt() {
        List<String> users = new CopyOnWriteArrayList<>();
        Supplier<Mono<?>> recording = () -> Mono.deferContextual(context -> {
            users.add(context.get("user"));
            return users.size() < 3 ? Mono.error(new IOException("again")) : Mono.just("done");
        });
        backend.script("retried", recording);
        StepVerifier.create(client.retried("a").contextWrite(Context.of("user", "ana")))
                .then(() -> advance(100)).then(() -> advance(100))
                .expectNext("done").expectComplete().verify(VERIFY);
        assertThat(users).containsExactly("ana", "ana", "ana");
    }

    // ---- circuit breaker

    @Test
    void theBreakerOpensRejectsWithoutCallingAndClosesAgain() {
        backend.script("guarded", failure("one"), failure("two"), value("probe"));
        StepVerifier.create(client.guarded("a")).expectError(IOException.class).verify(VERIFY);
        StepVerifier.create(client.guarded("a")).expectError(IOException.class).verify(VERIFY);
        assertThat(registry.circuitBreaker("mono-breaker").state()).isEqualTo(CircuitBreakerState.OPEN);

        StepVerifier.create(client.guarded("a")).expectError(CallNotPermittedException.class).verify(VERIFY);
        assertThat(backend.calls("guarded")).as("rejected before the delegate").isEqualTo(2);

        ResilienceTesting.elapseWaitInOpen(registry.circuitBreaker("mono-breaker"), clock);
        StepVerifier.create(client.guarded("a")).expectNext("probe").expectComplete().verify(VERIFY);
    }

    @Test
    void aCancelledCallIsNotRecorded() {
        backend.script("guarded", never());
        for (int i = 0; i < 5; i++) {
            client.guarded("a").subscribe().dispose();
        }
        assertThat(registry.circuitBreaker("mono-breaker").snapshot().bufferedCalls()).isZero();
        assertThat(backend.cancellations("guarded")).isEqualTo(5);
    }

    // ---- timeout

    @Test
    void aTimeoutCancelsTheDelegateAndFails() {
        backend.script("timed", never());
        StepVerifier.create(client.timed("a"))
                .then(() -> advance(499)).expectNoEvent(Duration.ZERO)
                .then(() -> advance(1))
                .expectError(CallTimeoutException.class).verify(VERIFY);
        assertThat(backend.cancellations("timed")).isOne();
        assertThat(events(TimeoutEvent.TimedOut.class)).hasSize(1);
        assertThat(clock.pendingTasks()).isZero();
    }

    @Test
    void aMonoInTimeLeavesNoTimer() {
        Sinks.One<String> sink = Sinks.one();
        backend.script("timed", from(sink));
        StepVerifier.create(client.timed("a")).then(() -> advance(200)).then(() -> sink.tryEmitValue("in time"))
                .expectNext("in time").expectComplete().verify(VERIFY);
        assertThat(clock.pendingTasks()).isZero();
        assertThat(events(TimeoutEvent.TimedOut.class)).isEmpty();
    }

    @Test
    void oneTimeoutAroundEveryAttemptWithPolicyOrder() {
        backend.script("oneTimeoutForAllAttempts", failure("one"), failure("two"), never());
        StepVerifier.create(client.oneTimeoutForAllAttempts("a"))
                .then(() -> advance(100)).then(() -> advance(100)).then(() -> advance(50))
                .expectError(CallTimeoutException.class).verify(VERIFY);
        assertThat(backend.calls("oneTimeoutForAllAttempts")).isEqualTo(3);
        assertThat(backend.cancellations("oneTimeoutForAllAttempts")).as("the third attempt").isOne();
        assertThat(events(RetryEvent.GaveUp.class)).as("the timeout is outside the retry").isEmpty();
    }

    // ---- bulkhead, rate limiter, adaptive limit

    @Test
    void aFullBulkheadRejectsAndADisposedCallFreesItsSlot() {
        backend.script("bulkheaded", never());
        Disposable held = client.bulkheaded("a").subscribe();
        assertThat(registry.bulkhead("mono-bulkhead").concurrentCalls()).isOne();
        StepVerifier.create(client.bulkheaded("b")).expectError(BulkheadFullException.class).verify(VERIFY);

        held.dispose();
        assertThat(registry.bulkhead("mono-bulkhead").concurrentCalls()).isZero();
        backend.script("bulkheaded", value("free"));
        StepVerifier.create(client.bulkheaded("c")).expectNext("free").expectComplete().verify(VERIFY);
        assertThat(registry.bulkhead("mono-bulkhead").concurrentCalls()).isZero();
    }

    @Test
    void aWaitingCallGetsTheSlotWhenItIsFreed() {
        Sinks.One<String> first = Sinks.one();
        backend.script("bulkheadedWithWait", from(first), value("second"));
        List<String> seen = new CopyOnWriteArrayList<>();
        client.bulkheadedWithWait("a").subscribe(seen::add);
        client.bulkheadedWithWait("b").subscribe(seen::add);
        assertThat(registry.bulkhead("mono-bulkhead-wait").waitingCalls()).isOne();

        first.tryEmitValue("first");
        Awaiting.until(() -> seen.size() == 2);
        // The slot is freed when the first attempt's Mono completes, before its value reaches the caller,
        // and the hand-off runs the second call on another thread: either value may arrive first.
        assertThat(seen).containsExactlyInAnyOrder("first", "second");
        assertThat(backend.calls("bulkheadedWithWait")).isEqualTo(2);
        Awaiting.until(() -> registry.bulkhead("mono-bulkhead-wait").concurrentCalls() == 0);
    }

    @Test
    void aWaitingCallThatIsCancelledLeavesTheQueue() {
        backend.script("bulkheadedWithWait", never());
        Disposable held = client.bulkheadedWithWait("a").subscribe();
        Disposable waiting = client.bulkheadedWithWait("b").subscribe();
        assertThat(registry.bulkhead("mono-bulkhead-wait").waitingCalls()).isOne();
        waiting.dispose();
        assertThat(registry.bulkhead("mono-bulkhead-wait").waitingCalls()).isZero();
        held.dispose();
        assertThat(registry.bulkhead("mono-bulkhead-wait").concurrentCalls()).isZero();
        assertThat(backend.calls("bulkheadedWithWait")).isOne();
    }

    @Test
    void anExhaustedRateLimiterRejectsOrDelaysTheSubscription() {
        backend.script("limited", value("v"));
        StepVerifier.create(client.limited("a")).expectNext("v").expectComplete().verify(VERIFY);
        StepVerifier.create(client.limited("a")).expectError(RateLimitExceededException.class).verify(VERIFY);
        assertThat(backend.calls("limited")).isOne();

        backend.script("limitedWithWait", value("v"));
        StepVerifier.create(client.limitedWithWait("a")).expectNext("v").expectComplete().verify(VERIFY);
        StepVerifier.create(client.limitedWithWait("a"))
                .then(() -> assertThat(backend.calls("limitedWithWait")).as("not yet").isOne())
                .then(() -> advance(1000))
                .expectNext("v").expectComplete().verify(VERIFY);
        assertThat(backend.calls("limitedWithWait")).isEqualTo(2);
    }

    @Test
    void aFullAdaptiveLimitRejects() {
        backend.script("adaptive", never());
        Disposable held = client.adaptive("a").subscribe();
        StepVerifier.create(client.adaptive("b")).expectError(LimitExceededException.class).verify(VERIFY);
        held.dispose();
        assertThat(registry.adaptiveLimit("mono-adaptive").inFlight()).isZero();
    }

    // ---- hedge

    @Test
    void aHedgeStartsASecondAttemptAndCancelsTheLoser() {
        Sinks.One<String> fast = Sinks.one();
        backend.script("hedged", never(), from(fast));
        StepVerifier.create(client.hedged("a"))
                .then(() -> advance(50))
                .then(() -> assertThat(backend.calls("hedged")).isEqualTo(2))
                .then(() -> fast.tryEmitValue("second wins"))
                .expectNext("second wins").expectComplete().verify(VERIFY);
        assertThat(backend.cancellations("hedged")).as("the first attempt").isOne();
        assertThat(events(HedgeEvent.Won.class)).hasSize(1);
    }

    @Test
    void aFastFirstAttemptStartsNoHedge() {
        backend.script("hedged", value("first"));
        StepVerifier.create(client.hedged("a")).expectNext("first").expectComplete().verify(VERIFY);
        clock.advanceUntilIdle();
        assertThat(backend.calls("hedged")).isOne();
        assertThat(clock.pendingTasks()).isZero();
    }

    // ---- fallback

    @Test
    void theFallbackMonoGetsTheFailureAndTheContext() {
        backend.script("recovered", failure("one"), failure("two"));
        StepVerifier.create(client.recovered("a").contextWrite(Context.of("user", "ana"))).then(() -> advance(100))
                .expectNext("recovered a from two for ana").expectComplete().verify(VERIFY);
        assertThat(events(FallbackEvent.Applied.class)).hasSize(1);
    }

    @Test
    void aHandlerReturningAValueRecoversATimeout() {
        backend.script("handled", never());
        StepVerifier.create(client.handled("a")).then(() -> advance(200))
                .expectNext("cached a after 1").expectComplete().verify(VERIFY);
        assertThat(backend.cancellations("handled")).isOne();
    }

    // ---- classifier

    @Test
    void blankResultsAreRetriedAndThenRejected() {
        backend.script("classified", value(" "), value(""), value("filled"));
        StepVerifier.create(client.classified("a")).then(() -> advance(100)).then(() -> advance(100))
                .expectNext("filled").expectComplete().verify(VERIFY);

        backend.script("classified", value(" "));
        StepVerifier.create(client.classified("a")).then(() -> advance(100)).then(() -> advance(100))
                .expectError(ResultRejectedException.class).verify(VERIFY);
    }

    @Test
    void anEmptyMonoIsANullResultForTheClassifier() {
        backend.script("classified", Mono::empty, value("after empty"));
        StepVerifier.create(client.classified("a")).then(() -> advance(100))
                .expectNext("after empty").expectComplete().verify(VERIFY);
    }

    // ---- a full stack

    @Test
    void aStackTimesOutEachAttemptRetriesAndFallsBack() {
        backend.script("stack", never());
        StepVerifier.create(client.stack("a"))
                .then(() -> advance(300)).then(() -> advance(100))
                .then(() -> advance(300)).then(() -> advance(100))
                .then(() -> advance(300))
                .assertNext(value -> assertThat(value).startsWith("recovered a from "))
                .expectComplete().verify(VERIFY);
        assertThat(backend.calls("stack")).isEqualTo(3);
        assertThat(backend.cancellations("stack")).as("every attempt timed out").isEqualTo(3);
        assertThat(events(TimeoutEvent.TimedOut.class)).hasSize(3);
        assertThat(registry.bulkhead("mono-stack").concurrentCalls()).isZero();
        assertThat(clock.pendingTasks()).isZero();
    }

    // ---- shapes

    @Test
    void aMonoOfVoidCompletesEmptyAndRetriesErrors() {
        backend.script("send", failure("one"), Mono::empty);
        StepVerifier.create(client.send("m")).then(() -> advance(100)).expectComplete().verify(VERIFY);
        assertThat(backend.calls("send")).isEqualTo(2);
    }

    @Test
    void wildcardAndGenericMonos() {
        backend.script("wildcard", failure("one"), () -> Mono.just(new StringBuilder("built")));
        StepVerifier.create(client.wildcard("a")).then(() -> advance(100))
                .assertNext(value -> assertThat(value).hasToString("built")).expectComplete().verify(VERIFY);

        backend.script("generic", () -> Mono.just(List.of(1, 2)));
        StepVerifier.create(client.generic(Integer.class)).expectNext(List.of(1, 2)).expectComplete().verify(VERIFY);
    }

    @Test
    void anUnannotatedMonoIsTheDelegatesOwn() {
        backend.script("plain", failure("not retried"));
        StepVerifier.create(client.plain("a")).expectErrorMessage("not retried").verify(VERIFY);
        assertThat(backend.calls("plain")).isOne();
    }

    @Test
    void concurrentSubscribersAreIndependentCalls() {
        Sinks.One<String> first = Sinks.one();
        Sinks.One<String> second = Sinks.one();
        backend.script("retried", from(first), from(second));
        List<String> seen = new CopyOnWriteArrayList<>();
        Mono<String> mono = client.retried("a");
        mono.subscribe(seen::add);
        mono.subscribe(seen::add);
        second.tryEmitValue("second");
        first.tryEmitValue("first");
        assertThat(seen).containsExactly("second", "first");
    }

    /** Waits for something another thread does (a bulkhead hand-off runs on a virtual thread). */
    static final class Awaiting {
        private Awaiting() {
        }

        static void until(java.util.function.BooleanSupplier condition) {
            long deadline = System.nanoTime() + VERIFY.toNanos();
            while (!condition.getAsBoolean()) {
                assertThat(System.nanoTime() - deadline).as("waited too long").isNegative();
                Thread.onSpinWait();
            }
        }
    }
}
