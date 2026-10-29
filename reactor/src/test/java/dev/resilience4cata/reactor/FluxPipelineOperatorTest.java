package dev.resilience4cata.reactor;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.CallTimeoutException;
import dev.resilience4cata.Classifier;
import dev.resilience4cata.Pipeline;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.RetryConfig.Backoff;
import dev.resilience4cata.RetryConfig.Jitter;
import dev.resilience4cata.testkit.ManualClock;
import dev.resilience4cata.testkit.ResilienceTesting;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

/** {@link FluxPipelineOperator}: a pipeline around streams of many elements, on a manual clock. */
class FluxPipelineOperatorTest {

    private static final Duration VERIFY = Duration.ofSeconds(5);

    private final ManualClock clock = new ManualClock();
    private final ResilienceRegistry registry = ResilienceTesting.registry(clock).build();
    private final AtomicInteger subscriptions = new AtomicInteger();

    private Pipeline.Builder<String> pipeline(String id) {
        return Pipeline.builder(registry, id, String.class)
                .retry(r -> r.maxAttempts(3).delay(Duration.ofMillis(100)).backoff(Backoff.FIXED).jitter(Jitter.NONE));
    }

    /** Fails before its first element on the first subscription, then emits a, b, c. */
    private final Flux<String> flaky = Flux.defer(() -> subscriptions.getAndIncrement() == 0
            ? Flux.<String>error(new IOException("connect"))
            : Flux.just("a", "b", "c"));

    @Test
    void eachAttemptSubscribesToTheSourceAgainBeforeTheFirstElement() {
        StepVerifier.create(flaky.transform(FluxPipelineOperator.of(pipeline("again").build())))
                .then(() -> clock.advance(Duration.ofMillis(100)))
                .expectNext("a", "b", "c").expectComplete().verify(VERIFY);
        assertThat(subscriptions).hasValue(2);
    }

    @Test
    void aFailureAfterTheFirstElementIsNotRetried() {
        Flux<String> broken = Flux.defer(() -> {
            subscriptions.incrementAndGet();
            return Flux.just("a", "b").concatWith(Flux.error(new IOException("reset")));
        });
        StepVerifier.create(broken.transform(FluxPipelineOperator.of(pipeline("committed").build())))
                .expectNext("a", "b").expectErrorMessage("reset").verify(VERIFY);
        assertThat(subscriptions).hasValue(1);
    }

    @Test
    void theFallbacksValueIsAStreamOfOneElement() {
        Pipeline<String> withFallback = pipeline("fallback").fallback(failure -> "cached").build();
        StepVerifier.create(Flux.<String>error(new IOException("down")).transform(FluxPipelineOperator.of(withFallback)))
                .then(() -> clock.advance(Duration.ofMillis(200)))
                .expectNext("cached").expectComplete().verify(VERIFY);
    }

    @Test
    void theTimeoutRestartsOnEveryElement() {
        Pipeline<String> timed = Pipeline.builder(registry, "idle", String.class)
                .timeout(t -> t.timeout(Duration.ofSeconds(1))).build();
        Flux<String> ticks = Flux.defer(() -> {
            reactor.core.publisher.Sinks.Many<String> sink = reactor.core.publisher.Sinks.many().unicast().onBackpressureBuffer();
            for (int i = 1; i <= 3; i++) {
                String element = String.valueOf(i);
                clock.schedule(Duration.ofMillis(600L * i).toNanos(), () -> sink.tryEmitNext(element));
            }
            clock.schedule(Duration.ofMillis(2400).toNanos(), sink::tryEmitComplete);
            return sink.asFlux();
        });
        StepVerifier.create(ticks.transform(FluxPipelineOperator.of(timed)))
                .then(() -> clock.advance(Duration.ofMillis(2400)))
                .expectNext("1", "2", "3").expectComplete().verify(VERIFY);
        StepVerifier.create(Flux.<String>never().transform(FluxPipelineOperator.of(timed)))
                .then(() -> clock.advance(Duration.ofSeconds(1)))
                .expectError(CallTimeoutException.class).verify(VERIFY);
    }

    @Test
    void theSubscribersContextReachesTheSource() {
        Flux<String> source = Flux.deferContextual(context -> Flux.just(context.<String>get("tenant")));
        StepVerifier.create(source.transform(FluxPipelineOperator.of(pipeline("context").build()))
                        .contextWrite(Context.of("tenant", "acme")))
                .expectNext("acme").expectComplete().verify(VERIFY);
    }

    @Test
    void deferBuildsTheSourceForEveryAttempt() {
        AtomicInteger built = new AtomicInteger();
        FluxPipelineOperator<String> operator = FluxPipelineOperator.of(pipeline("defer").build());
        StepVerifier.create(operator.defer(() -> built.incrementAndGet() == 1
                        ? Flux.error(new IOException("x")) : Flux.just("built " + built.get())))
                .then(() -> clock.advance(Duration.ofMillis(100)))
                .expectNext("built 2").expectComplete().verify(VERIFY);
        StepVerifier.create(operator.defer(() -> {
            throw new IllegalStateException("cannot build");
        })).then(() -> clock.advance(Duration.ofMillis(200))).expectErrorMessage("cannot build").verify(VERIFY);
    }

    @Test
    void aPipelineWithAHedgeOrAClassifierCannotProtectAFlux() {
        Pipeline<String> hedged = Pipeline.builder(registry, "hedged", String.class).hedge().build();
        StepVerifier.create(Flux.just("a").transform(FluxPipelineOperator.of(hedged)))
                .expectErrorSatisfies(failure -> assertThat(failure).isInstanceOf(IllegalStateException.class)
                        .hasMessageStartingWith("[R4C-RT-020] Pipeline 'hedged' has a hedge"))
                .verify(VERIFY);
        Pipeline<String> classified = pipeline("classified").classifier((result, failure) -> Classifier.Outcome.SUCCESS)
                .build();
        StepVerifier.create(Flux.just("a").transform(FluxPipelineOperator.of(classified)))
                .expectErrorSatisfies(failure -> assertThat(failure).hasMessageContaining("has a classifier"))
                .verify(VERIFY);
    }

    @Test
    void describesItsPipeline() {
        assertThat(FluxPipelineOperator.of(pipeline("named").build())).hasToString("FluxPipelineOperator[Pipeline[named, retry]]");
    }
}
