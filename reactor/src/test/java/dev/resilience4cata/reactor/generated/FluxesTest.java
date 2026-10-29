package dev.resilience4cata.reactor.generated;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.BulkheadFullException;
import dev.resilience4cata.CallTimeoutException;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.RetryConfig.Backoff;
import dev.resilience4cata.RetryConfig.Jitter;
import dev.resilience4cata.event.ResilienceEvent;
import dev.resilience4cata.event.RetryEvent;
import dev.resilience4cata.generated.AdaptiveLimitPolicy;
import dev.resilience4cata.generated.BulkheadPolicy;
import dev.resilience4cata.generated.CircuitBreakerPolicy;
import dev.resilience4cata.generated.FallbackPolicy;
import dev.resilience4cata.generated.RetryPolicy;
import dev.resilience4cata.generated.TimeoutPolicy;
import dev.resilience4cata.testkit.ManualClock;
import dev.resilience4cata.testkit.ResilienceTesting;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscription;
import reactor.core.Disposable;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

/**
 * {@link Fluxes} used the way generated code uses it: {@code defer} around the policies, the layers inside
 * the retry or fallback in {@link FluxCall#attempt}, the streaming form of the timeout, breaker and
 * adaptive limit, and {@link FluxCall#subscribe} around the delegate's {@code Flux}. Real policies from a
 * registry on a {@link ManualClock}, so every delay and timeout runs on the test thread.
 */
class FluxesTest {

    private static final Duration VERIFY = Duration.ofSeconds(5);
    private static final long SECOND = 1_000_000_000L;

    private final ManualClock clock = new ManualClock();
    private final List<ResilienceEvent> events = new CopyOnWriteArrayList<>();
    private final ResilienceRegistry registry = ResilienceTesting.registry(clock)
            .listen(ResilienceEvent.class, events::add).build();

    /** The delegate: each call is one attempt, scripted by {@link #attempts}; counts subscriptions and cancels. */
    private final List<Supplier<Flux<String>>> attempts = new ArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger cancellations = new AtomicInteger();
    private final List<Long> requests = new CopyOnWriteArrayList<>();
    private final List<Object> discarded = new CopyOnWriteArrayList<>();

    @AfterEach
    void resetHooks() {
        Hooks.resetOnNextDropped();
    }

    private Flux<String> delegate() {
        int attempt = calls.getAndIncrement();
        Flux<String> flux = attempts.get(Math.min(attempt, attempts.size() - 1)).get();
        return flux.doOnRequest(requests::add).doOnCancel(cancellations::incrementAndGet);
    }

    /** A stream whose elements arrive on the manual clock: {@code "a@500"} is {@code a} at 500 ms after subscribing. */
    private Supplier<Flux<String>> timed(String... elements) {
        return () -> Flux.defer(() -> {
            Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
            for (String element : elements) {
                String[] parts = element.split("@");
                long at = Long.parseLong(parts[1]) * 1_000_000L;
                Runnable emit = switch (parts[0]) {
                    case "!" -> () -> sink.tryEmitError(new IOException("broken"));
                    case "." -> sink::tryEmitComplete;
                    default -> () -> sink.tryEmitNext(parts[0]);
                };
                clock.schedule(at, emit);
            }
            return sink.asFlux();
        });
    }

    private RetryPolicy retry(String id, int attempts) {
        return registry.retryPolicy(id, "", "", r -> r.maxAttempts(attempts).delay(Duration.ofMillis(100))
                .backoff(Backoff.FIXED).jitter(Jitter.NONE));
    }

    private TimeoutPolicy timeout(String id, Duration timeout) {
        return registry.timeoutPolicy(id, "", "", t -> t.timeout(timeout));
    }

    /** Retry around a timeout around the delegate, as a generated method with both. */
    private Flux<String> retriedWithTimeout(RetryPolicy retry, TimeoutPolicy timeout) {
        return Fluxes.<String>defer(stream -> retry.executeAsync(() -> stream.attempt(
                attempt -> timeout.executeStreamAsync(() -> attempt.subscribe(delegate()), attempt))));
    }

    // ---- laziness, elements and demand

    @Test
    void nothingRunsBeforeASubscriptionAndEachSubscriptionIsOneCall() {
        attempts.add(() -> Flux.just("a", "b"));
        RetryPolicy retry = retry("lazy", 3);
        Flux<String> flux = Fluxes.<String>defer(stream -> retry.executeAsync(() -> stream.attempt(
                attempt -> attempt.subscribe(delegate()))));
        assertThat(calls).hasValue(0);
        StepVerifier.create(flux).expectNext("a", "b").expectComplete().verify(VERIFY);
        StepVerifier.create(flux).expectNext("a", "b").expectComplete().verify(VERIFY);
        assertThat(calls).hasValue(2);
    }

    @Test
    void elementsFollowTheSubscribersDemand() {
        attempts.add(() -> Flux.range(1, 10).map(String::valueOf));
        Flux<String> flux = Fluxes.<String>defer(stream -> stream.attempt(attempt -> attempt.subscribe(delegate())));
        StepVerifier.create(flux, 0)
                .expectSubscription().expectNoEvent(Duration.ofMillis(10))
                .thenRequest(2).expectNext("1", "2")
                .thenRequest(3).expectNext("3", "4", "5")
                .thenCancel().verify(VERIFY);
        assertThat(requests).containsExactly(2L, 3L);
    }

    @Test
    void anEmptyStreamCompletes() {
        attempts.add(Flux::empty);
        Flux<String> flux = Fluxes.<String>defer(stream -> stream.attempt(attempt -> attempt.subscribe(delegate())));
        StepVerifier.create(flux).expectComplete().verify(VERIFY);
    }

    @Test
    void aFailureArrivesWithoutDemand() {
        attempts.add(() -> Flux.error(new IOException("x")));
        Flux<String> flux = Fluxes.<String>defer(stream -> stream.attempt(attempt -> attempt.subscribe(delegate())));
        StepVerifier.create(flux, 0).expectError(IOException.class).verify(VERIFY);
    }

    @Test
    void aNonPositiveRequestOfACoreSubscriberIsReportedAndIgnoredAsByReactorsOperators() {
        attempts.add(() -> Flux.just("a"));
        Flux<String> flux = Fluxes.<String>defer(stream -> stream.single(attempt -> attempt.subscribe(delegate())));
        List<String> signals = new CopyOnWriteArrayList<>();
        flux.subscribe(new BaseSubscriber<>() {
            @Override
            protected void hookOnSubscribe(Subscription subscription) {
                subscription.request(0);
                subscription.request(1);
            }

            @Override
            protected void hookOnNext(String value) {
                signals.add(value);
            }

            @Override
            protected void hookOnComplete() {
                signals.add("complete");
            }

            @Override
            protected void hookOnError(Throwable failure) {
                signals.add("error");
            }
        });
        assertThat(signals).containsExactly("a", "complete");
    }

    @Test
    void anOutsideSubscriberGetsRule39FromReactorsStrictSubscriber() {
        attempts.add(() -> Flux.never());
        Flux<String> flux = Fluxes.<String>defer(stream -> stream.single(attempt -> attempt.subscribe(delegate())));
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        flux.subscribe(new org.reactivestreams.Subscriber<String>() {
            @Override
            public void onSubscribe(Subscription subscription) {
                subscription.request(-1);
            }

            @Override
            public void onNext(String value) {
                // none
            }

            @Override
            public void onError(Throwable failure) {
                failures.add(failure);
            }

            @Override
            public void onComplete() {
                // none
            }
        });
        assertThat(failures).singleElement().isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anEndThatArrivesFromInsideOnNextIsDeliveredAfterIt() {
        // A timeout listens, so elements go through the serializer; the source completes inside the request that the
        // subscriber makes from onNext, so the end arrives on the delivering thread while onNext runs.
        attempts.add(() -> Flux.from(subscriber -> subscriber.onSubscribe(new Subscription() {
            private int requests;

            @Override
            public void request(long n) {
                if (requests++ == 0) {
                    subscriber.onNext("a");
                } else {
                    subscriber.onComplete();
                }
            }

            @Override
            public void cancel() {
                // nothing to stop
            }
        })));
        TimeoutPolicy timeout = timeout("inside", Duration.ofSeconds(1));
        Flux<String> flux = Fluxes.<String>defer(stream -> stream.single(
                attempt -> timeout.executeStreamAsync(() -> attempt.subscribe(delegate()), attempt)));
        List<String> signals = new CopyOnWriteArrayList<>();
        java.util.concurrent.atomic.AtomicBoolean inside = new java.util.concurrent.atomic.AtomicBoolean();
        BaseSubscriber<String> subscriber = new BaseSubscriber<>() {
            @Override
            protected void hookOnSubscribe(Subscription subscription) {
                // no demand yet: the call starts (its end is then delivered through the policies) before the element
            }

            @Override
            protected void hookOnNext(String value) {
                inside.set(true);
                signals.add("next " + value);
                request(1); // the source completes inside this call
                signals.add("onNext returns");
                inside.set(false);
            }

            @Override
            protected void hookOnComplete() {
                signals.add(inside.get() ? "complete inside onNext" : "complete");
            }
        };
        flux.subscribe(subscriber);
        subscriber.request(1);
        assertThat(signals).containsExactly("next a", "onNext returns", "complete");
        assertThat(clock.pendingTasks()).isZero();
    }

    @Test
    void theSubscribersContextReachesEveryAttempt() {
        List<String> seen = new CopyOnWriteArrayList<>();
        attempts.add(() -> Flux.deferContextual(context -> {
            seen.add(context.get("who"));
            return Flux.error(new IOException("x"));
        }));
        attempts.add(() -> Flux.deferContextual(context -> {
            seen.add(context.get("who"));
            return Flux.just("ok");
        }));
        Flux<String> flux = Fluxes.<String>defer(stream -> retry("context", 2).executeAsync(() -> stream.attempt(
                attempt -> attempt.subscribe(delegate()))));
        StepVerifier.create(flux.contextWrite(Context.of("who", "me")))
                .then(() -> clock.advance(Duration.ofMillis(100)))
                .expectNext("ok").expectComplete().verify(VERIFY);
        assertThat(seen).containsExactly("me", "me");
    }

    @Test
    void aNullFluxIsAFailedAttempt() {
        attempts.add(() -> null);
        attempts.add(() -> Flux.just("ok"));
        Flux<String> flux = Fluxes.<String>defer(stream -> retry("null", 2).executeAsync(() -> stream.attempt(
                attempt -> attempt.subscribe(delegate()))));
        StepVerifier.create(flux).then(() -> clock.advance(Duration.ofMillis(100)))
                .expectNext("ok").expectComplete().verify(VERIFY);
    }

    // ---- retry and fallback before the first element only

    @Test
    void aFailureBeforeTheFirstElementIsRetried() {
        attempts.add(() -> Flux.error(new IOException("x")));
        attempts.add(() -> Flux.just("a", "b"));
        Flux<String> flux = Fluxes.<String>defer(stream -> retry("before", 3).executeAsync(() -> stream.attempt(
                attempt -> attempt.subscribe(delegate()))));
        StepVerifier.create(flux).then(() -> clock.advance(Duration.ofMillis(100)))
                .expectNext("a", "b").expectComplete().verify(VERIFY);
        assertThat(calls).hasValue(2);
    }

    @Test
    void aFailureAfterTheFirstElementReachesTheSubscriberAndIsNotRetried() {
        attempts.add(() -> Flux.just("a").concatWith(Flux.error(new IOException("broken"))));
        Flux<String> flux = Fluxes.<String>defer(stream -> retry("after", 3).executeAsync(() -> stream.attempt(
                attempt -> attempt.subscribe(delegate()))));
        StepVerifier.create(flux).expectNext("a").expectErrorMessage("broken").verify(VERIFY);
        assertThat(calls).hasValue(1);
        assertThat(events).noneMatch(event -> event instanceof RetryEvent);
        assertThat(clock.pendingTasks()).isZero();
    }

    @Test
    void theFallbackReplacesAStreamThatFailedBeforeItsFirstElement() {
        attempts.add(() -> Flux.error(new IOException("x")));
        FallbackPolicy fallback = registry.fallbackPolicy("fallback", List.of(Throwable.class), List.of());
        Flux<String> flux = Fluxes.<String>defer(stream -> retry("fallback", 2).executeAsync(
                () -> stream.attempt(attempt -> attempt.subscribe(delegate())), fallback,
                (failure, attempts) -> stream.recover(Flux.just("cached " + attempts, "values"))));
        StepVerifier.create(flux).then(() -> clock.advance(Duration.ofMillis(100)))
                .expectNext("cached 2", "values").expectComplete().verify(VERIFY);
    }

    @Test
    void theFallbackDoesNotRunAfterTheFirstElement() {
        attempts.add(() -> Flux.just("a").concatWith(Flux.error(new IOException("broken"))));
        FallbackPolicy fallback = registry.fallbackPolicy("no-fallback", List.of(Throwable.class), List.of());
        AtomicInteger recovered = new AtomicInteger();
        Flux<String> flux = Fluxes.<String>defer(stream -> fallback.executeAsync(
                () -> stream.attempt(attempt -> attempt.subscribe(delegate())),
                (failure, attempts) -> {
                    recovered.incrementAndGet();
                    return stream.recover(Flux.just("fallback"));
                }));
        StepVerifier.create(flux).expectNext("a").expectErrorMessage("broken").verify(VERIFY);
        assertThat(recovered).hasValue(0);
    }

    @Test
    void theDemandCarriesOverToTheNextAttempt() {
        attempts.add(() -> Flux.error(new IOException("x")));
        attempts.add(() -> Flux.range(1, 10).map(String::valueOf));
        Flux<String> flux = Fluxes.<String>defer(stream -> retry("demand", 2).executeAsync(() -> stream.attempt(
                attempt -> attempt.subscribe(delegate()))));
        StepVerifier.create(flux, 3)
                .then(() -> clock.advance(Duration.ofMillis(100)))
                .expectNext("1", "2", "3")
                .expectNoEvent(Duration.ofMillis(10))
                .thenRequest(1).expectNext("4")
                .thenCancel().verify(VERIFY);
        assertThat(requests).containsExactly(3L, 3L, 1L);
    }

    // ---- the timeout restarts on every element

    @Test
    void aStreamThatKeepsEmittingDoesNotTimeOut() {
        attempts.add(timed("a@600", "b@1200", "c@1800", ".@2400"));
        Flux<String> flux = retriedWithTimeout(retry("steady", 2), timeout("steady", Duration.ofSeconds(1)));
        StepVerifier.create(flux)
                .then(() -> clock.advance(Duration.ofMillis(2400)))
                .expectNext("a", "b", "c").expectComplete().verify(VERIFY);
        assertThat(clock.pendingTasks()).isZero();
    }

    @Test
    void aSilenceAfterTheFirstElementTimesOutAndIsNotRetried() {
        attempts.add(timed("a@300", "b@2000"));
        Flux<String> flux = retriedWithTimeout(retry("silence", 3), timeout("silence", Duration.ofSeconds(1)));
        StepVerifier.create(flux)
                .then(() -> clock.advance(Duration.ofMillis(300)))
                .expectNext("a")
                .then(() -> clock.advance(Duration.ofMillis(999)))
                .expectNoEvent(Duration.ofMillis(10))
                .then(() -> clock.advance(Duration.ofMillis(1)))
                .expectError(CallTimeoutException.class).verify(VERIFY);
        assertThat(calls).hasValue(1);
        assertThat(cancellations).hasValue(1);
        clock.advanceUntilIdle();
        assertThat(clock.pendingTasks()).isZero();
    }

    @Test
    void aTimeoutBeforeTheFirstElementIsRetriedAndALateElementOfTheOldAttemptIsDiscarded() {
        attempts.add(timed("late@1500"));
        attempts.add(timed("fresh@100", ".@200"));
        Flux<String> flux = retriedWithTimeout(retry("retried", 2), timeout("retried", Duration.ofSeconds(1)));
        StepVerifier.create(flux)
                .then(() -> clock.advance(Duration.ofMillis(1000))) // the first attempt times out
                .then(() -> clock.advance(Duration.ofMillis(100)))  // the retry's delay
                .then(() -> clock.advance(Duration.ofMillis(200)))
                .expectNext("fresh").expectComplete().verify(VERIFY);
        assertThat(calls).hasValue(2);
        assertThat(cancellations).as("the first attempt").hasValue(1);
        clock.advanceUntilIdle();
    }

    @Test
    void anAttemptGivenUpOnCannotEmitOnceTheRetryMovedOn() {
        // A publisher that ignores cancel, as one racing its cancel does: only the gate stops its element.
        attempts.add(() -> Flux.from(subscriber -> {
            subscriber.onSubscribe(new Subscription() {
                @Override
                public void request(long n) {
                    // emits on the clock, whatever was requested or cancelled
                }

                @Override
                public void cancel() {
                    cancellations.incrementAndGet();
                }
            });
            clock.schedule(1_500_000_000L, () -> {
                subscriber.onNext("stale");
                subscriber.onComplete();
            });
        }));
        attempts.add(timed("fresh@100", "more@1000", ".@1100"));
        Flux<String> flux = retriedWithTimeout(retry("gate", 2), timeout("gate", Duration.ofSeconds(1)))
                .doOnDiscard(String.class, discarded::add); // the subscriber's discard hook
        StepVerifier.create(flux)
                .then(() -> clock.advance(Duration.ofMillis(1000)))  // attempt 1 times out
                .then(() -> clock.advance(Duration.ofMillis(100)))   // the retry's delay; attempt 2 starts
                .then(() -> clock.advance(Duration.ofMillis(100)))
                .expectNext("fresh")
                .then(() -> clock.advance(Duration.ofMillis(300)))   // attempt 1's element arrives now
                .then(() -> clock.advance(Duration.ofMillis(700)))
                .expectNext("more")
                .then(() -> clock.advance(Duration.ofMillis(100)))
                .expectComplete().verify(VERIFY);
        assertThat(discarded).containsExactly("stale");
    }

    @Test
    void anAttemptThatStartsLateNeitherEmitsNorTakesTheDemand() {
        // As a bulkhead's hand-off after the attempt's timeout fired: the attempt failed, the fallback's stream runs,
        // and only then do the attempt's layers subscribe to the delegate.
        List<FluxCall.Attempt<String>> late = new ArrayList<>();
        Sinks.Many<String> fallbackStream = Sinks.many().unicast().onBackpressureBuffer();
        FallbackPolicy fallback = registry.fallbackPolicy("late", List.of(Throwable.class), List.of());
        Flux<String> flux = Fluxes.<String>defer(stream -> fallback.executeAsync(
                () -> stream.attempt(attempt -> {
                    late.add(attempt);
                    return java.util.concurrent.CompletableFuture.failedFuture(new IOException("timed out in the queue"));
                }),
                (failure, attempts) -> stream.recover(fallbackStream.asFlux()))).doOnDiscard(String.class, discarded::add);
        StepVerifier.create(flux, 1)
                .then(() -> fallbackStream.tryEmitNext("fallback 1"))
                .expectNext("fallback 1")
                .then(() -> late.getFirst().subscribe(Flux.just("stale 1", "stale 2")))
                .thenRequest(1)
                .then(() -> fallbackStream.tryEmitNext("fallback 2"))
                .expectNext("fallback 2")
                .then(fallbackStream::tryEmitComplete)
                .expectComplete().verify(VERIFY);
        assertThat(discarded).as("nothing of the late attempt reached the subscriber").doesNotContain("fallback 1", "fallback 2");
    }

    @Test
    void anAttemptThatStartsLateCannotCommitBeforeTheFallbacksStream() {
        // The late attempt subscribes before the fallback's stream emitted, to a publisher that emits without demand
        // (as one racing its cancel): only its own, closed number stops it.
        List<FluxCall.Attempt<String>> late = new ArrayList<>();
        Sinks.Many<String> fallbackStream = Sinks.many().unicast().onBackpressureBuffer();
        FallbackPolicy fallback = registry.fallbackPolicy("late-first", List.of(Throwable.class), List.of());
        Flux<String> eager = Flux.from(subscriber -> {
            subscriber.onSubscribe(new Subscription() {
                @Override
                public void request(long n) {
                    // ignored
                }

                @Override
                public void cancel() {
                    // ignored
                }
            });
            subscriber.onNext("stale");
            subscriber.onComplete();
        });
        Flux<String> flux = Fluxes.<String>defer(stream -> fallback.executeAsync(
                () -> stream.attempt(attempt -> {
                    late.add(attempt);
                    return java.util.concurrent.CompletableFuture.failedFuture(new IOException("timed out in the queue"));
                }),
                (failure, attempts) -> stream.recover(fallbackStream.asFlux()))).doOnDiscard(String.class, discarded::add);
        StepVerifier.create(flux)
                .then(() -> late.getFirst().subscribe(eager))
                .then(() -> fallbackStream.tryEmitNext("fallback"))
                .then(fallbackStream::tryEmitComplete)
                .expectNext("fallback")
                .expectComplete().verify(VERIFY);
        assertThat(discarded).containsExactly("stale");
    }

    // ---- policies that hold something for the whole stream

    @Test
    void theBulkheadHoldsItsSlotUntilTheStreamEndsAndACancelFreesIt() {
        BulkheadPolicy bulkhead = registry.bulkheadPolicy("slot", "", "", b -> b.maxConcurrent(1));
        attempts.add(timed("a@100", "b@5000", ".@6000"));
        Flux<String> flux = Fluxes.<String>defer(stream -> stream.attempt(
                attempt -> bulkhead.executeAsync(() -> attempt.subscribe(delegate()))));
        List<String> seen = new CopyOnWriteArrayList<>();
        Disposable first = flux.subscribe(seen::add);
        clock.advance(Duration.ofMillis(100));
        assertThat(seen).containsExactly("a");
        assertThat(bulkhead.concurrentCalls()).as("after the first element").isOne();
        StepVerifier.create(flux).expectError(BulkheadFullException.class).verify(VERIFY);

        first.dispose();
        assertThat(cancellations).hasValue(1);
        assertThat(bulkhead.concurrentCalls()).isZero();
        clock.advanceUntilIdle();
        assertThat(seen).containsExactly("a");
    }

    @Test
    void theBreakerRecordsAFailureAfterElements() {
        CircuitBreakerPolicy breaker = registry.circuitBreakerPolicy("broken", "", "", b -> b.windowSize(2).minimumCalls(2));
        attempts.add(() -> Flux.just("a").concatWith(Flux.error(new IOException("broken"))));
        Flux<String> flux = Fluxes.<String>defer(stream -> stream.attempt(
                attempt -> breaker.executeStreamAsync(() -> attempt.subscribe(delegate()), attempt)));
        StepVerifier.create(flux).expectNext("a").expectError(IOException.class).verify(VERIFY);
        assertThat(registry.snapshot().circuitBreakers().getFirst().failedCalls()).isOne();
    }

    @Test
    void theBreakerTimesSlowCallsUntilTheFirstElement() {
        CircuitBreakerPolicy breaker = registry.circuitBreakerPolicy("slow", "", "", b -> b.windowSize(2).minimumCalls(2)
                .slowCallDuration(Duration.ofSeconds(1)));
        attempts.add(timed("a@200", "b@5000", ".@6000"));
        Flux<String> flux = Fluxes.<String>defer(stream -> stream.attempt(
                attempt -> breaker.executeStreamAsync(() -> attempt.subscribe(delegate()), attempt)));
        StepVerifier.create(flux).then(() -> clock.advance(Duration.ofSeconds(6)))
                .expectNext("a", "b").expectComplete().verify(VERIFY);
        assertThat(registry.snapshot().circuitBreakers().getFirst().slowCalls()).isZero();
    }

    @Test
    void theAdaptiveLimitSamplesTheFirstElementAndHoldsItsSlotForTheStream() {
        AdaptiveLimitPolicy limit = registry.adaptiveLimitPolicy("limit", "", "", l -> l.initialLimit(1)
                .minWindowSamples(1).window(Duration.ofSeconds(1)));
        attempts.add(timed("a@300", ".@4000"));
        Flux<String> flux = Fluxes.<String>defer(stream -> stream.attempt(
                attempt -> limit.executeStreamAsync(() -> attempt.subscribe(delegate()), attempt)));
        List<String> seen = new CopyOnWriteArrayList<>();
        flux.subscribe(seen::add);
        clock.advance(Duration.ofMillis(300));
        assertThat(limit.inFlight()).isOne();
        clock.advance(Duration.ofMillis(3700));
        assertThat(limit.inFlight()).isZero();
        assertThat(limit.snapshot().windowLatency()).isEqualTo(Duration.ofMillis(300));
    }

    @Test
    void cancellingTheSubscriptionCancelsTheCallThroughEveryPolicy() {
        BulkheadPolicy bulkhead = registry.bulkheadPolicy("cancel", "", "", b -> b.maxConcurrent(1));
        TimeoutPolicy timeout = timeout("cancel", Duration.ofSeconds(1));
        attempts.add(timed("a@100", "b@200", "c@300"));
        Flux<String> flux = Fluxes.<String>defer(stream -> retry("cancel", 3).executeAsync(() -> stream.attempt(
                attempt -> timeout.executeStreamAsync(() -> bulkhead.executeAsync(() -> attempt.subscribe(delegate())), attempt))));
        StepVerifier.create(flux)
                .then(() -> clock.advance(Duration.ofMillis(150)))
                .expectNext("a")
                .thenCancel().verify(VERIFY);
        assertThat(cancellations).hasValue(1);
        assertThat(bulkhead.concurrentCalls()).isZero();
        clock.advanceUntilIdle();
        assertThat(clock.pendingTasks()).isZero();
        assertThat(events).noneMatch(event -> event instanceof RetryEvent);
    }

    @Test
    void elementsAfterTheEndAreDiscarded() {
        List<Object> dropped = new CopyOnWriteArrayList<>();
        Hooks.onNextDropped(dropped::add);
        attempts.add(timed("a@100", "b@1500"));
        TimeoutPolicy timeout = timeout("late", Duration.ofSeconds(1));
        Flux<String> flux = Fluxes.<String>defer(stream -> stream.attempt(
                attempt -> timeout.executeStreamAsync(() -> attempt.subscribe(delegate()), attempt)));
        StepVerifier.create(flux)
                .then(() -> clock.advance(Duration.ofMillis(100)))
                .expectNext("a")
                .then(() -> clock.advance(Duration.ofSeconds(1)))
                .expectError(CallTimeoutException.class).verify(VERIFY);
        clock.advanceUntilIdle();
        assertThat(cancellations).hasValue(1);
    }
}
