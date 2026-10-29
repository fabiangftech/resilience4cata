package dev.resilience4cata.reactor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.resilience4cata.BulkheadFullException;
import dev.resilience4cata.CallNotPermittedException;
import dev.resilience4cata.CallTimeoutException;
import dev.resilience4cata.Pipeline;
import dev.resilience4cata.RateLimitExceededException;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.event.RetryEvent;
import dev.resilience4cata.testkit.ManualClock;
import dev.resilience4cata.testkit.ResilienceTesting;
import dev.resilience4cata.RetryConfig.Backoff;
import dev.resilience4cata.RetryConfig.Jitter;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

/** {@link PipelineOperator}: a pipeline applied to a {@code Mono}, on a manual clock. */
class PipelineOperatorTest {

    private static final Duration VERIFY = Duration.ofSeconds(5);

    private final ManualClock clock = new ManualClock();
    private final List<RetryEvent> retryEvents = new CopyOnWriteArrayList<>();
    private final ResilienceRegistry registry = ResilienceTesting.registry(clock)
            .listen(RetryEvent.class, retryEvents::add)
            .build();

    private final AtomicInteger subscriptions = new AtomicInteger();

    /** Fails {@code failures} times, then emits {@code value}; counts subscriptions. */
    private Mono<String> failing(int failures, String value) {
        return Mono.defer(() -> subscriptions.incrementAndGet() <= failures
                ? Mono.<String>error(new IOException("attempt " + subscriptions.get()))
                : Mono.just(value));
    }

    private Pipeline.Builder<String> pipeline(String id) {
        return Pipeline.builder(registry, id, String.class);
    }

    @Test
    void transformResubscribesToTheSourceForEveryAttempt() {
        PipelineOperator<String> operator = PipelineOperator.of(pipeline("transform")
                .retry(r -> r.maxAttempts(3).delay(Duration.ofMillis(10)).backoff(Backoff.FIXED).jitter(Jitter.NONE)).build());

        Mono<String> mono = failing(2, "third").transform(operator);
        assertThat(subscriptions).as("lazy").hasValue(0);
        StepVerifier.create(mono)
                .then(() -> clock.advance(Duration.ofMillis(10)))
                .then(() -> clock.advance(Duration.ofMillis(10)))
                .expectNext("third")
                .expectComplete().verify(VERIFY);
        assertThat(subscriptions).hasValue(3);
        assertThat(retryEvents).hasSize(2).allMatch(RetryEvent.Retrying.class::isInstance);
    }

    @Test
    void deferBuildsTheCallAgainForEveryAttempt() {
        PipelineOperator<String> operator = PipelineOperator.of(pipeline("defer")
                .retry(r -> r.maxAttempts(2).delay(Duration.ofMillis(10)).backoff(Backoff.FIXED).jitter(Jitter.NONE)).build());
        AtomicInteger built = new AtomicInteger();

        Mono<String> mono = operator.defer(() -> built.incrementAndGet() == 1
                ? Mono.error(new IOException("first")) : Mono.just("built " + built.get()));
        StepVerifier.create(mono).then(() -> clock.advance(Duration.ofMillis(10))).expectNext("built 2").expectComplete().verify(VERIFY);
    }

    @Test
    void aSupplierThatThrowsOrReturnsNullIsAFailedAttempt() {
        PipelineOperator<String> operator = PipelineOperator.of(pipeline("bad-supplier")
                .retry(r -> r.maxAttempts(3).delay(Duration.ofMillis(10)).backoff(Backoff.FIXED).jitter(Jitter.NONE)).build());
        AtomicInteger built = new AtomicInteger();

        Mono<String> mono = operator.defer(() -> switch (built.incrementAndGet()) {
            case 1 -> throw new IllegalStateException("thrown");
            case 2 -> null;
            default -> Mono.just("third");
        });
        StepVerifier.create(mono)
                .then(() -> clock.advance(Duration.ofMillis(10)))
                .then(() -> clock.advance(Duration.ofMillis(10)))
                .expectNext("third")
                .expectComplete().verify(VERIFY);
    }

    @Test
    void theRetryGivesUpWithTheLastFailure() {
        PipelineOperator<String> operator = PipelineOperator.of(pipeline("give-up")
                .retry(r -> r.maxAttempts(2).delay(Duration.ofMillis(10)).backoff(Backoff.FIXED).jitter(Jitter.NONE)).build());

        StepVerifier.create(failing(5, "never").transform(operator))
                .then(() -> clock.advance(Duration.ofMillis(10)))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(IOException.class).hasMessage("attempt 2"))
                .verify(VERIFY);
        assertThat(retryEvents).last().isInstanceOf(RetryEvent.GaveUp.class);
    }

    @Test
    void theFallbackGetsTheFailure() {
        PipelineOperator<String> operator = PipelineOperator.of(pipeline("fallback")
                .retry(r -> r.maxAttempts(2).delay(Duration.ofMillis(10)).backoff(Backoff.FIXED).jitter(Jitter.NONE))
                .fallback(failure -> "fallback for " + failure.getMessage())
                .build());

        StepVerifier.create(failing(5, "never").transform(operator))
                .then(() -> clock.advance(Duration.ofMillis(10)))
                .expectNext("fallback for attempt 2")
                .expectComplete().verify(VERIFY);
    }

    @Test
    void theRejectionsOfEveryPolicyAreEmittedAsErrors() {
        Pipeline<String> breaker = pipeline("open").circuitBreaker().build();
        registry.circuitBreaker("open").forceOpen();
        StepVerifier.create(Mono.just("x").transform(PipelineOperator.of(breaker)))
                .expectError(CallNotPermittedException.class).verify(VERIFY);

        PipelineOperator<String> bulkhead = PipelineOperator.of(pipeline("full").bulkhead(b -> b.maxConcurrent(1)).build());
        Disposable held = Mono.<String>never().transform(bulkhead).subscribe();
        StepVerifier.create(Mono.just("x").transform(bulkhead)).expectError(BulkheadFullException.class).verify(VERIFY);
        held.dispose();
        StepVerifier.create(Mono.just("x").transform(bulkhead)).expectNext("x").expectComplete().verify(VERIFY);

        PipelineOperator<String> limiter = PipelineOperator.of(pipeline("limited")
                .rateLimiter(r -> r.permits(1).period(Duration.ofSeconds(1))).build());
        StepVerifier.create(Mono.just("x").transform(limiter)).expectNext("x").expectComplete().verify(VERIFY);
        StepVerifier.create(Mono.just("x").transform(limiter)).expectError(RateLimitExceededException.class).verify(VERIFY);
    }

    @Test
    void aTimeoutCancelsTheSource() {
        PipelineOperator<String> operator = PipelineOperator.of(pipeline("timeout")
                .timeout(t -> t.timeout(Duration.ofMillis(200))).build());
        AtomicInteger cancelled = new AtomicInteger();

        StepVerifier.create(Mono.<String>never().doOnCancel(cancelled::incrementAndGet).transform(operator))
                .then(() -> clock.advance(Duration.ofMillis(200)))
                .expectError(CallTimeoutException.class)
                .verify(VERIFY);
        assertThat(cancelled).hasValue(1);
    }

    @Test
    void aTimeoutInsideTheRetryIsRetried() {
        PipelineOperator<String> operator = PipelineOperator.of(pipeline("timeout-retry")
                .retry(r -> r.maxAttempts(2).delay(Duration.ofMillis(10)).backoff(Backoff.FIXED).jitter(Jitter.NONE))
                .timeout(t -> t.timeout(Duration.ofMillis(200)))
                .build());
        Sinks.One<String> second = Sinks.one();
        AtomicInteger attempt = new AtomicInteger();

        Mono<String> mono = operator.defer(() -> attempt.incrementAndGet() == 1 ? Mono.never() : second.asMono());
        StepVerifier.create(mono)
                .then(() -> clock.advance(Duration.ofMillis(200)))
                .then(() -> clock.advance(Duration.ofMillis(10)))
                .then(() -> second.tryEmitValue("second"))
                .expectNext("second")
                .expectComplete().verify(VERIFY);
    }

    @Test
    void aHedgeSubscribesAgainAndCancelsTheLoser() {
        PipelineOperator<String> operator = PipelineOperator.of(pipeline("hedge")
                .hedge(h -> h.maxAttempts(2).delay(Duration.ofMillis(50))).build());
        Sinks.One<String> slow = Sinks.one();
        Sinks.One<String> fast = Sinks.one();
        AtomicInteger attempt = new AtomicInteger();
        AtomicInteger slowCancelled = new AtomicInteger();

        Mono<String> mono = operator.defer(() -> attempt.getAndIncrement() == 0
                ? slow.asMono().doOnCancel(slowCancelled::incrementAndGet) : fast.asMono());
        StepVerifier.create(mono)
                .then(() -> clock.advance(Duration.ofMillis(50)))
                .then(() -> fast.tryEmitValue("fast"))
                .expectNext("fast")
                .expectComplete().verify(VERIFY);
        assertThat(slowCancelled).hasValue(1);
    }

    @Test
    void theContextReachesTheSource() {
        PipelineOperator<String> operator = PipelineOperator.of(pipeline("context").retry().build());

        Mono<String> mono = Mono.deferContextual(view -> Mono.just(view.<String>get("tenant"))).transform(operator);
        StepVerifier.create(mono.contextWrite(Context.of("tenant", "acme"))).expectNext("acme").expectComplete().verify(VERIFY);
    }

    @Test
    void cancellingTheSubscriptionCancelsTheSourceAndStopsTheRetry() {
        PipelineOperator<String> operator = PipelineOperator.of(pipeline("cancel")
                .retry(r -> r.maxAttempts(3).delay(Duration.ofMillis(10)).backoff(Backoff.FIXED).jitter(Jitter.NONE))
                .bulkhead(b -> b.maxConcurrent(1))
                .build());
        AtomicInteger cancelled = new AtomicInteger();

        Disposable subscription = Mono.<String>never().doOnSubscribe(s -> subscriptions.incrementAndGet())
                .doOnCancel(cancelled::incrementAndGet).transform(operator).subscribe();
        subscription.dispose();
        clock.advanceUntilIdle();
        assertThat(cancelled).hasValue(1);
        assertThat(subscriptions).hasValue(1);
        assertThat(registry.bulkhead("cancel").concurrentCalls()).isZero();
        assertThat(retryEvents).as("a cancel is not a failure").isEmpty();
    }

    @Test
    void anEmptySourceIsAnEmptyResult() {
        PipelineOperator<String> operator = PipelineOperator.of(pipeline("empty").retry().build());
        StepVerifier.create(Mono.<String>empty().transform(operator)).expectComplete().verify(VERIFY);
    }

    @Test
    void anOperatorIsReusableAndRejectsNull() {
        PipelineOperator<String> operator = PipelineOperator.of(pipeline("reuse").retry().build());
        for (int i = 0; i < 3; i++) {
            StepVerifier.create(Mono.just("v" + i).transform(operator)).expectNext("v" + i).expectComplete().verify(VERIFY);
        }
        assertThat(operator).hasToString("PipelineOperator[Pipeline[reuse, retry]]");
        assertThatThrownBy(() -> PipelineOperator.of(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> operator.apply(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> operator.defer(null)).isInstanceOf(NullPointerException.class);
    }
}
