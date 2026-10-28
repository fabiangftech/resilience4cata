package dev.resilience4cata.reactor.generated;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.BulkheadFullException;
import dev.resilience4cata.CallNotPermittedException;
import dev.resilience4cata.CallTimeoutException;
import dev.resilience4cata.CircuitBreakerState;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.generated.BulkheadPolicy;
import dev.resilience4cata.generated.CircuitBreakerPolicy;
import dev.resilience4cata.generated.FallbackPolicy;
import dev.resilience4cata.generated.HedgePolicy;
import dev.resilience4cata.generated.RetryPolicy;
import dev.resilience4cata.generated.TimeoutPolicy;
import dev.resilience4cata.testkit.ManualClock;
import dev.resilience4cata.testkit.ResilienceTesting;
import dev.resilience4cata.RetryConfig.Backoff;
import dev.resilience4cata.RetryConfig.Jitter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

/**
 * {@link Monos} used the way generated code uses it: {@code defer} around the policies' nested
 * {@code executeAsync} calls, {@code stage} around the delegate's {@code Mono}. Real policies from a
 * registry on a {@link ManualClock}, so every delay, timeout and hedge runs on the test thread.
 */
class MonosTest {

    private static final Duration VERIFY = Duration.ofSeconds(5);

    private final ManualClock clock = new ManualClock();
    private final ResilienceRegistry registry = ResilienceTesting.registry(clock).build();

    /** A delegate whose {@code Mono} counts subscriptions and cancellations. */
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger subscriptions = new AtomicInteger();
    private final AtomicInteger cancellations = new AtomicInteger();

    private <T> Mono<T> tracked(Mono<T> mono) {
        calls.incrementAndGet();
        return mono.doOnSubscribe(s -> subscriptions.incrementAndGet()).doOnCancel(cancellations::incrementAndGet);
    }

    private RetryPolicy retry(String id, int attempts) {
        return registry.retryPolicy(id, "", "", r -> r.maxAttempts(attempts).delay(Duration.ofMillis(100)).backoff(Backoff.FIXED).jitter(Jitter.NONE));
    }

    private TimeoutPolicy timeout(String id, Duration timeout) {
        return registry.timeoutPolicy(id, "", "", t -> t.timeout(timeout));
    }

    // ---- laziness and subscriptions

    @Test
    void nothingRunsBeforeASubscription() {
        RetryPolicy retry = retry("lazy", 3);
        Mono<String> mono = Monos.defer(context -> retry.executeAsync(
                () -> Monos.stage(tracked(Mono.just("a")), context)));

        assertThat(calls).as("assembling the Mono calls nothing").hasValue(0);
        StepVerifier.create(mono).expectNext("a").expectComplete().verify(VERIFY);
        assertThat(calls).hasValue(1);
        assertThat(subscriptions).hasValue(1);
    }

    @Test
    void everySubscriptionIsANewCallThroughThePolicies() {
        RetryPolicy retry = retry("resubscribe", 3);
        AtomicInteger n = new AtomicInteger();
        Mono<Integer> mono = Monos.defer(context -> retry.executeAsync(
                () -> Monos.stage(tracked(Mono.fromSupplier(n::incrementAndGet)), context)));

        StepVerifier.create(mono).expectNext(1).expectComplete().verify(VERIFY);
        StepVerifier.create(mono).expectNext(2).expectComplete().verify(VERIFY);
        StepVerifier.create(mono.repeat(2)).expectNext(3, 4, 5).expectComplete().verify(VERIFY);
        assertThat(calls).hasValue(5);
    }

    @Test
    void concurrentSubscribersGetIndependentCalls() {
        RetryPolicy retry = retry("independent", 2);
        Sinks.One<String> first = Sinks.one();
        Sinks.One<String> second = Sinks.one();
        List<Sinks.One<String>> sinks = List.of(first, second);
        AtomicInteger index = new AtomicInteger();
        Mono<String> mono = Monos.defer(context -> retry.executeAsync(
                () -> Monos.stage(sinks.get(index.getAndIncrement()).asMono(), context)));

        List<String> seen = new CopyOnWriteArrayList<>();
        mono.subscribe(seen::add);
        mono.subscribe(seen::add);
        second.tryEmitValue("second");
        first.tryEmitValue("first");
        assertThat(seen).containsExactly("second", "first");
    }

    // ---- outcomes

    @Test
    void anEmptyMonoIsASuccessWithNoValue() {
        RetryPolicy retry = retry("empty", 3);
        Mono<String> mono = Monos.defer(context -> retry.executeAsync(
                () -> Monos.stage(tracked(Mono.<String>empty()), context)));

        StepVerifier.create(mono).expectComplete().verify(VERIFY);
        assertThat(calls).as("empty is not a failure: no retry").hasValue(1);
    }

    @Test
    void anEmptyMonoAfterADelayIsAlsoEmpty() {
        TimeoutPolicy timeout = timeout("empty-later", Duration.ofSeconds(1));
        Sinks.Empty<String> sink = Sinks.empty();
        Mono<String> mono = Monos.defer(context -> timeout.executeAsync(() -> Monos.stage(sink.asMono(), context)));

        StepVerifier.create(mono).then(sink::tryEmitEmpty).expectComplete().verify(VERIFY);
    }

    @Test
    void anErrorIsRetriedAndThenEmittedUnwrapped() {
        RetryPolicy retry = retry("error", 3);
        IOException failure = new IOException("down");
        Mono<String> mono = Monos.defer(context -> retry.executeAsync(
                () -> Monos.stage(tracked(Mono.<String>error(failure)), context)));

        StepVerifier.create(mono)
                .then(() -> clock.advance(Duration.ofMillis(100)))
                .then(() -> clock.advance(Duration.ofMillis(100)))
                .expectErrorSatisfies(e -> assertThat(e).isSameAs(failure))
                .verify(VERIFY);
        assertThat(calls).hasValue(3);
        assertThat(subscriptions).as("every attempt subscribes to its own Mono").hasValue(3);
    }

    @Test
    void anErrorWrappedInACompletionExceptionIsUnwrapped() {
        RetryPolicy retry = retry("wrapped", 1);
        IOException failure = new IOException("inside");
        Mono<String> mono = Monos.defer(context -> retry.executeAsync(
                () -> Monos.stage(Mono.<String>error(new CompletionException(failure)), context)));

        StepVerifier.create(mono).expectErrorSatisfies(e -> assertThat(e).isSameAs(failure)).verify(VERIFY);
    }

    @Test
    void aFailureThenASuccessEmitsTheSuccess() {
        RetryPolicy retry = retry("recover", 3);
        AtomicInteger attempt = new AtomicInteger();
        Mono<String> mono = Monos.defer(context -> retry.executeAsync(() -> Monos.stage(
                attempt.incrementAndGet() == 1 ? Mono.<String>error(new IOException("once")) : Mono.just("ok"), context)));

        StepVerifier.create(mono).then(() -> clock.advance(Duration.ofMillis(100))).expectNext("ok").expectComplete().verify(VERIFY);
        assertThat(attempt).hasValue(2);
    }

    @Test
    void aDelegateThatThrowsBeforeReturningAMonoIsAFailedAttempt() {
        RetryPolicy retry = retry("throws", 2);
        AtomicInteger attempt = new AtomicInteger();
        Mono<String> mono = Monos.defer(context -> retry.executeAsync(() -> {
            if (attempt.incrementAndGet() == 1) {
                throw new UncheckedIOException(new IOException("assembly"));
            }
            return Monos.stage(Mono.just("second"), context);
        }));

        StepVerifier.create(mono).then(() -> clock.advance(Duration.ofMillis(100))).expectNext("second").expectComplete().verify(VERIFY);
    }

    @Test
    void aDelegateThatReturnsNullIsAFailedAttempt() {
        RetryPolicy retry = retry("null", 2);
        Mono<String> mono = Monos.defer(context -> retry.executeAsync(() -> Monos.<String>stage(null, context)));

        StepVerifier.create(mono)
                .then(() -> clock.advance(Duration.ofMillis(100)))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(NullPointerException.class)
                        .hasMessageContaining("returned null instead of a Mono"))
                .verify(VERIFY);
    }

    @Test
    void aValueIsNotEmittedBeforeItIsRequested() {
        RetryPolicy retry = retry("backpressure", 1);
        Mono<String> mono = Monos.defer(context -> retry.executeAsync(() -> Monos.stage(Mono.just("v"), context)));

        StepVerifier.create(mono, 0)
                .expectSubscription()
                .expectNoEvent(Duration.ofMillis(20))
                .thenRequest(1)
                .expectNext("v")
                .expectComplete().verify(VERIFY);
    }

    // ---- context

    @Test
    void theSubscribersContextReachesTheDelegatesMono() {
        RetryPolicy retry = retry("context", 1);
        Mono<String> mono = Monos.defer(context -> retry.executeAsync(() -> Monos.stage(
                Mono.deferContextual(view -> Mono.just(view.<String>get("user"))), context)));

        StepVerifier.create(mono.contextWrite(Context.of("user", "ana"))).expectNext("ana").expectComplete().verify(VERIFY);
    }

    @Test
    void theContextReachesEveryAttempt() {
        RetryPolicy retry = retry("context-retry", 3);
        List<String> seen = new ArrayList<>();
        Mono<String> mono = Monos.defer(context -> retry.executeAsync(() -> Monos.stage(
                Mono.deferContextual(view -> {
                    seen.add(view.get("trace"));
                    return seen.size() < 3 ? Mono.<String>error(new IOException("again")) : Mono.just("done");
                }), context)));

        StepVerifier.create(mono.contextWrite(Context.of("trace", "t-1")))
                .then(() -> clock.advance(Duration.ofMillis(100)))
                .then(() -> clock.advance(Duration.ofMillis(100)))
                .expectNext("done")
                .expectComplete().verify(VERIFY);
        assertThat(seen).containsExactly("t-1", "t-1", "t-1");
    }

    @Test
    void theStagesContextIsTheOneGiven() {
        Context context = Context.of("k", "v");
        StageSubscriber<String> subscriber = new StageSubscriber<>(context);
        assertThat(subscriber.currentContext()).isSameAs(context);
        CompletionStage<String> stage = Monos.stage(
                Mono.deferContextual(view -> Mono.just(view.<String>get("k"))).delaySubscription(Duration.ZERO), context);
        assertThat(stage.toCompletableFuture().join()).isEqualTo("v");
    }

    // ---- cancellation

    @Test
    void cancellingTheSubscriptionCancelsTheDelegatesMono() {
        RetryPolicy retry = retry("cancel", 3);
        Mono<String> mono = Monos.defer(context -> retry.executeAsync(
                () -> Monos.stage(tracked(Mono.<String>never()), context)));

        Disposable subscription = mono.subscribe();
        assertThat(subscriptions).hasValue(1);
        subscription.dispose();
        assertThat(cancellations).hasValue(1);
        clock.advanceUntilIdle();
        assertThat(calls).as("no further attempt").hasValue(1);
    }

    @Test
    void cancellingFreesTheBulkheadSlot() {
        BulkheadPolicy bulkhead = registry.bulkheadPolicy("slot", "", "", b -> b.maxConcurrent(1));
        Mono<String> mono = Monos.defer(context -> bulkhead.executeAsync(
                () -> Monos.stage(tracked(Mono.<String>never()), context)));

        Disposable held = mono.subscribe();
        assertThat(registry.bulkhead("slot").concurrentCalls()).isOne();
        StepVerifier.create(mono).expectError(BulkheadFullException.class).verify(VERIFY);

        held.dispose();
        assertThat(registry.bulkhead("slot").concurrentCalls()).isZero();
        assertThat(cancellations).hasValue(1);
        StepVerifier.create(mono).thenCancel().verify(VERIFY);
        assertThat(registry.bulkhead("slot").concurrentCalls()).as("never released twice").isZero();
    }

    @Test
    void aCancelledCallIsNotRecordedByTheBreaker() {
        CircuitBreakerPolicy breaker = registry.circuitBreakerPolicy("breaker", "", "",
                b -> b.windowSize(2).minimumCalls(2).failureRateThreshold(50));
        Mono<String> mono = Monos.defer(context -> breaker.executeAsync(
                () -> Monos.stage(Mono.<String>never(), context)));

        for (int i = 0; i < 10; i++) {
            mono.subscribe().dispose();
        }
        assertThat(registry.circuitBreaker("breaker").state()).isEqualTo(CircuitBreakerState.CLOSED);
        assertThat(registry.circuitBreaker("breaker").snapshot().bufferedCalls()).isZero();
    }

    @Test
    void anOpenBreakerRejectsWithoutSubscribingToTheDelegate() {
        CircuitBreakerPolicy breaker = registry.circuitBreakerPolicy("open", "", "", null);
        registry.circuitBreaker("open").forceOpen();
        Mono<String> mono = Monos.defer(context -> breaker.executeAsync(
                () -> Monos.stage(tracked(Mono.just("never")), context)));

        StepVerifier.create(mono).expectError(CallNotPermittedException.class).verify(VERIFY);
        assertThat(calls).hasValue(0);
    }

    @Test
    void aTimeoutCancelsTheDelegatesMonoAndFails() {
        TimeoutPolicy timeout = timeout("timeout", Duration.ofMillis(500));
        Mono<String> mono = Monos.defer(context -> timeout.executeAsync(
                () -> Monos.stage(tracked(Mono.<String>never()), context)));

        StepVerifier.create(mono)
                .then(() -> clock.advance(Duration.ofMillis(499)))
                .expectNoEvent(Duration.ZERO)
                .then(() -> clock.advance(Duration.ofMillis(1)))
                .expectError(CallTimeoutException.class)
                .verify(VERIFY);
        assertThat(cancellations).hasValue(1);
        assertThat(clock.pendingTasks()).isZero();
    }

    @Test
    void aMonoThatCompletesInTimeLeavesNoTimer() {
        TimeoutPolicy timeout = timeout("in-time", Duration.ofMillis(500));
        Sinks.One<String> sink = Sinks.one();
        Mono<String> mono = Monos.defer(context -> timeout.executeAsync(() -> Monos.stage(sink.asMono(), context)));

        StepVerifier.create(mono)
                .then(() -> clock.advance(Duration.ofMillis(100)))
                .then(() -> sink.tryEmitValue("fast"))
                .expectNext("fast")
                .expectComplete().verify(VERIFY);
        assertThat(clock.pendingTasks()).isZero();
    }

    @Test
    void aSynchronousMonoSkipsTheTimeoutTimer() {
        TimeoutPolicy timeout = timeout("sync", Duration.ofMillis(500));
        Mono<String> mono = Monos.defer(context -> timeout.executeAsync(() -> Monos.stage(Mono.just("now"), context)));

        StepVerifier.create(mono).expectNext("now").expectComplete().verify(VERIFY);
        assertThat(clock.pendingTasks()).isZero();
        assertThat(Monos.stage(Mono.just("x"), Context.empty()).getClass()).isEqualTo(CompletableFuture.class);
        assertThat(Monos.stage(Mono.empty(), Context.empty()).toCompletableFuture().getNow("absent")).isNull();
    }

    @Test
    void aHedgeCancelsTheAttemptThatLost() {
        HedgePolicy hedge = registry.hedgePolicy("hedge", "", "", h -> h.maxAttempts(2).delay(Duration.ofMillis(50)));
        Sinks.One<String> slow = Sinks.one();
        Sinks.One<String> fast = Sinks.one();
        AtomicInteger attempt = new AtomicInteger();
        List<String> cancelled = new CopyOnWriteArrayList<>();
        Mono<String> mono = Monos.defer(context -> hedge.executeAsync(() -> {
            Sinks.One<String> sink = attempt.getAndIncrement() == 0 ? slow : fast;
            String name = sink == slow ? "slow" : "fast";
            return Monos.stage(sink.asMono().doOnCancel(() -> cancelled.add(name)), context);
        }));

        StepVerifier.create(mono)
                .then(() -> clock.advance(Duration.ofMillis(50)))
                .then(() -> fast.tryEmitValue("fast"))
                .expectNext("fast")
                .expectComplete().verify(VERIFY);
        assertThat(cancelled).containsExactly("slow");
    }

    @Test
    void cancellingDuringARetryDelayStopsTheRetry() {
        RetryPolicy retry = retry("cancel-delay", 3);
        Mono<String> mono = Monos.defer(context -> retry.executeAsync(
                () -> Monos.stage(tracked(Mono.<String>error(new IOException("x"))), context)));

        StepVerifier.create(mono).then(() -> assertThat(clock.pendingTasks()).isOne()).thenCancel().verify(VERIFY);
        clock.advanceUntilIdle();
        assertThat(calls).hasValue(1);
        assertThat(clock.pendingTasks()).isZero();
    }

    // ---- fallback

    @Test
    void aFallbackMonoReplacesTheFailure() {
        FallbackPolicy fallback = registry.fallbackPolicy("fallback", List.of(Throwable.class), List.of());
        RetryPolicy retry = retry("fallback", 2);
        Mono<String> mono = Monos.defer(context -> retry.executeAsync(
                () -> Monos.stage(Mono.<String>error(new IOException("x")), context), fallback,
                (failure, attempts) -> Monos.stage(Mono.just("fallback after " + attempts), context)));

        StepVerifier.create(mono)
                .then(() -> clock.advance(Duration.ofMillis(100)))
                .expectNext("fallback after 2")
                .expectComplete().verify(VERIFY);
    }

    @Test
    void theFallbackSeesTheContextToo() {
        FallbackPolicy fallback = registry.fallbackPolicy("fallback-context", List.of(Throwable.class), List.of());
        Mono<String> mono = Monos.defer(context -> fallback.executeAsync(
                () -> Monos.stage(Mono.<String>error(new IOException("x")), context),
                (failure, attempts) -> Monos.stage(Mono.deferContextual(v -> Mono.just(v.<String>get("who"))), context)));

        StepVerifier.create(mono.contextWrite(Context.of("who", "fallback"))).expectNext("fallback").expectComplete().verify(VERIFY);
    }

    // ---- many subscriptions leave nothing behind

    @Test
    void manyCancelledSubscriptionsLeaveNoTimersAndNoSlots() {
        RetryPolicy retry = retry("many", 3);
        TimeoutPolicy timeout = timeout("many", Duration.ofSeconds(1));
        BulkheadPolicy bulkhead = registry.bulkheadPolicy("many", "", "", b -> b.maxConcurrent(10_000));
        Supplier<Mono<String>> source = () -> tracked(Mono.never());
        Mono<String> mono = Monos.defer(context -> retry.executeAsync(() -> timeout.executeAsync(
                () -> bulkhead.executeAsync(() -> Monos.stage(source.get(), context)))));

        List<Disposable> held = new ArrayList<>();
        for (int i = 0; i < 2_000; i++) {
            held.add(mono.subscribe());
        }
        assertThat(registry.bulkhead("many").concurrentCalls()).isEqualTo(2_000);
        held.forEach(Disposable::dispose);
        assertThat(registry.bulkhead("many").concurrentCalls()).isZero();
        assertThat(cancellations).hasValue(2_000);
        assertThat(clock.pendingTasks()).as("every timeout timer was cancelled").isZero();
        clock.advanceUntilIdle();
        assertThat(calls).hasValue(2_000);
    }
}
