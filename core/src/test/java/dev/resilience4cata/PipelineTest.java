package dev.resilience4cata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import dev.resilience4cata.event.FallbackEvent;
import dev.resilience4cata.event.RetryEvent;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.UndeclaredThrowableException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PipelineTest {

    private final ResilienceRegistry registry = ResilienceRegistry.builder()
            .retryDefaults(r -> r.delay(Duration.ZERO).jitter(RetryConfig.Jitter.NONE))
            .retry("five", r -> r.maxAttempts(5))
            .circuitBreaker("tiny", cb -> cb.windowSize(2).minimumCalls(2).failureRateThreshold(50)
                    .waitInOpen(Duration.ofHours(1)))
            .build();

    private static Throwable failureOf(CompletableFuture<?> future) {
        assertThat(future).isCompletedExceptionally();
        return future.handle((value, failure) -> failure).join();
    }

    /** Fails with {@code failure} the first {@code failures} times, then returns {@code "ok"}. */
    private static PipelineCall<String, IOException> failing(AtomicInteger calls, int failures, IOException failure) {
        return () -> {
            if (calls.incrementAndGet() <= failures) {
                throw failure;
            }
            return "ok";
        };
    }

    @Test
    void retriesUntilTheCallSucceeds() throws IOException {
        Pipeline<String> pipeline = Pipeline.builder(registry, "retry", String.class).retry().build();
        AtomicInteger calls = new AtomicInteger();

        assertThat(pipeline.execute(failing(calls, 2, new IOException("down")))).isEqualTo("ok");
        assertThat(calls).hasValue(3);
    }

    @Test
    void theCallsCheckedExceptionIsThrownAsIsOnceRetriesGiveUp() {
        Pipeline<String> pipeline = Pipeline.builder(registry, "retry-five", String.class).retry("five").build();
        AtomicInteger calls = new AtomicInteger();
        IOException down = new IOException("down");

        assertThatThrownBy(() -> pipeline.execute(failing(calls, 100, down))).isSameAs(down);
        assertThat(calls).hasValue(5);
    }

    @Test
    void overridesApplyOnTopOfTheNamedConfig() {
        Pipeline<String> pipeline = Pipeline.builder(registry, "retry-override", String.class)
                .retry("five", r -> r.maxAttempts(2)).build();
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> pipeline.execute(failing(calls, 100, new IOException()))).isInstanceOf(IOException.class);
        assertThat(calls).hasValue(2);
    }

    @Test
    void theFallbackRunsOnceRetriesGiveUpAndSeesTheAttempts() throws IOException {
        List<FallbackContext> contexts = new ArrayList<>();
        Pipeline<String> pipeline = Pipeline.builder(registry, "fallback-retry", String.class)
                .retry()
                .fallbackHandler(context -> {
                    contexts.add(context);
                    return "fallback";
                })
                .build();
        IOException down = new IOException("down");

        assertThat(pipeline.execute(failing(new AtomicInteger(), 100, down))).isEqualTo("fallback");
        assertThat(contexts).singleElement().satisfies(context -> {
            assertThat(context.attempts()).isEqualTo(3);
            assertThat(context.failure()).isSameAs(down);
            assertThat(context.id()).isEqualTo("fallback-retry");
            assertThat(context.argumentCount()).isZero();
        });
    }

    @Test
    void aFallbackWithoutRetryCountsOneAttempt() throws IOException {
        AtomicInteger attempts = new AtomicInteger();
        Pipeline<String> pipeline = Pipeline.builder(registry, "fallback-only", String.class)
                .fallbackHandler(context -> {
                    attempts.set(context.attempts());
                    return "fallback";
                })
                .build();

        assertThat(pipeline.execute(failing(new AtomicInteger(), 1, new IOException()))).isEqualTo("fallback");
        assertThat(attempts).hasValue(1);
        assertThat(pipeline.execute(() -> "direct")).isEqualTo("direct");
    }

    @Test
    void theFallbackPublishesEventsToTheRegistryListeners() throws IOException {
        List<FallbackEvent.Applied> events = new ArrayList<>();
        ResilienceRegistry listened = ResilienceRegistry.builder()
                .listen(FallbackEvent.Applied.class, events::add)
                .build();
        Pipeline<String> pipeline = Pipeline.builder(listened, "fallback-events", String.class)
                .fallback(failure -> "fallback")
                .build();

        IOException failure = new IOException();
        assertThat(pipeline.execute(failing(new AtomicInteger(), 1, failure))).isEqualTo("fallback");
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.id()).isEqualTo("fallback-events");
            assertThat(event.failure()).isSameAs(failure);
        });
    }

    @Test
    void theFallbackOnlyHandlesTheSelectedFailures() throws IOException {
        Pipeline<String> pipeline = Pipeline.builder(registry, "fallback-selected", String.class)
                .fallback(failure -> "fallback")
                .fallbackApplyOn(List.of(IOException.class))
                .fallbackSkipOn(List.of(FileNotFoundException.class))
                .build();

        assertThat(pipeline.execute(() -> {
            throw new IOException();
        })).isEqualTo("fallback");
        assertThatThrownBy(() -> pipeline.execute(() -> {
            throw new FileNotFoundException();
        })).isInstanceOf(FileNotFoundException.class);
        assertThatThrownBy(() -> pipeline.execute(() -> {
            throw new IllegalStateException();
        })).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aFallbackMayRethrowTheFailureButOtherCheckedExceptionsAreWrapped() {
        IOException down = new IOException("down");
        Pipeline<String> rethrows = Pipeline.builder(registry, "fallback-rethrows", String.class)
                .fallbackHandler(context -> {
                    throw context.failure();
                })
                .build();
        Pipeline<String> wraps = Pipeline.builder(registry, "fallback-wraps", String.class)
                .fallbackHandler(context -> {
                    throw new Exception("other");
                })
                .build();

        assertThatThrownBy(() -> rethrows.execute(failing(new AtomicInteger(), 1, down))).isSameAs(down);
        PipelineCall<String, IOException> fails = failing(new AtomicInteger(), 1, down);
        assertThatThrownBy(() -> wraps.execute(fails))
                .isInstanceOf(UndeclaredThrowableException.class)
                .hasRootCauseMessage("other");
    }

    @Test
    void anOpenBreakerRejectsAndStopsTheRetries() {
        Pipeline<String> pipeline = Pipeline.builder(registry, "breaker", String.class)
                .retry("five")
                .circuitBreaker("tiny")
                .build();
        AtomicInteger calls = new AtomicInteger();
        PipelineCall<String, IOException> fails = failing(calls, 100, new IOException());

        assertThatThrownBy(() -> pipeline.execute(fails))
                .as("two failures open the breaker; the third attempt is rejected and not retried")
                .isInstanceOf(CallNotPermittedException.class);
        assertThat(calls).hasValue(2);
        CircuitBreakerHandle handle = registry.circuitBreaker("breaker");
        assertThat(handle.state()).isEqualTo(CircuitBreakerState.OPEN);

        handle.reset();
        assertThat(pipeline.execute(() -> "ok")).isEqualTo("ok");
    }

    @Test
    void anOpenBreakerStopsTheRetriesWithACustomAbortOn() {
        List<RetryEvent.GaveUp> gaveUp = new ArrayList<>();
        ResilienceRegistry abortOnRegistry = ResilienceRegistry.builder()
                .retryDefaults(r -> r.maxAttempts(10).delay(Duration.ZERO).jitter(RetryConfig.Jitter.NONE)
                        .abortOn(List.of(IllegalArgumentException.class)))
                .listen(RetryEvent.GaveUp.class, gaveUp::add)
                .build();
        Pipeline<String> pipeline = Pipeline.builder(abortOnRegistry, "breaker-abort-on", String.class)
                .retry()
                .circuitBreaker(cb -> cb.windowSize(2).minimumCalls(2).failureRateThreshold(50)
                        .waitInOpen(Duration.ofHours(1)))
                .build();
        AtomicInteger calls = new AtomicInteger();
        PipelineCall<String, IOException> fails = failing(calls, 100, new IOException());

        assertThatThrownBy(() -> pipeline.execute(fails))
                .isInstanceOf(CallNotPermittedException.class);
        assertThat(calls).as("two failures open the breaker").hasValue(2);

        abortOnRegistry.circuitBreaker("breaker-abort-on").reset();
        CompletableFuture<String> async = pipeline.executeAsync(() -> {
            calls.incrementAndGet();
            return CompletableFuture.failedFuture(new IOException());
        });
        assertThat(async).failsWithin(5, TimeUnit.SECONDS).withThrowableThat()
                .havingCause().isInstanceOf(CallNotPermittedException.class);
        assertThat(calls).hasValue(4);
        assertThat(gaveUp).as("the third attempt is rejected and not retried, sync and async")
                .extracting(RetryEvent.GaveUp::attempts, RetryEvent.GaveUp::reason)
                .containsExactly(tuple(3, RetryEvent.Reason.NOT_RETRYABLE),
                        tuple(3, RetryEvent.Reason.NOT_RETRYABLE));
    }

    @Test
    void theFallbackHandlesRejections() {
        Pipeline<String> pipeline = Pipeline.builder(registry, "breaker-fallback", String.class)
                .circuitBreaker()
                .fallback(failure -> failure.getClass().getSimpleName())
                .build();
        registry.circuitBreaker("breaker-fallback").forceOpen();

        assertThat(pipeline.execute(() -> "ok")).isEqualTo("CallNotPermittedException");
    }

    @Test
    void theRateLimiterRejectsBeyondItsPermits() {
        Pipeline<String> pipeline = Pipeline.builder(registry, "limited", String.class)
                .rateLimiter(rl -> rl.permits(1).period(Duration.ofHours(1)))
                .build();

        assertThat(pipeline.execute(() -> "ok")).isEqualTo("ok");
        assertThatThrownBy(() -> pipeline.execute(() -> "ok")).isInstanceOf(RateLimitExceededException.class);
        assertThat(registry.rateLimiter("limited").availablePermits()).isZero();
    }

    // java:S2925: the call must really block so that the real watchdog interrupts it
    @SuppressWarnings("java:S2925")
    @Test
    void theTimeoutInterruptsALongCall() {
        Pipeline<String> pipeline = Pipeline.builder(registry, "timed", String.class)
                .timeout(t -> t.timeout(Duration.ofMillis(50)))
                .build();

        assertThatThrownBy(() -> pipeline.execute(() -> {
            Thread.sleep(10_000);
            return "late";
        })).isInstanceOf(CallTimeoutException.class);
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
        assertThat(pipeline.execute(() -> "ok")).isEqualTo("ok");
    }

    @Test
    void theBulkheadRejectsWhenFullAndFreesItsSlot() throws Exception {
        Pipeline<String> pipeline = Pipeline.builder(registry, "bulkhead", String.class)
                .bulkhead(b -> b.maxConcurrent(1))
                .build();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = Thread.ofVirtual().start(() -> {
            try {
                pipeline.execute(() -> {
                    entered.countDown();
                    release.await();
                    return "held";
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> pipeline.execute(() -> "ok")).isInstanceOf(BulkheadFullException.class);
        release.countDown();
        holder.join();
        assertThat(registry.bulkhead("bulkhead").concurrentCalls()).isZero();
        assertThat(pipeline.execute(() -> "ok")).isEqualTo("ok");
    }

    @Test
    void theBreakerIsInsideTheRetry() {
        Pipeline<String> pipeline = Pipeline.builder(registry, "order", String.class)
                .retry(r -> r.maxAttempts(3))
                .circuitBreaker(cb -> cb.windowSize(3).minimumCalls(3).failureRateThreshold(100)
                        .waitInOpen(Duration.ofHours(1)))
                .build();

        assertThatThrownBy(() -> pipeline.execute(failing(new AtomicInteger(), 100, new IOException())))
                .isInstanceOf(IOException.class);
        assertThat(registry.circuitBreaker("order").state())
                .as("every attempt went through the breaker").isEqualTo(CircuitBreakerState.OPEN);
    }

    @Test
    void pipelinesWithTheSameIdShareTheirInstances() {
        Pipeline<String> first = Pipeline.builder(registry, "shared", String.class).circuitBreaker("tiny").build();
        Pipeline<Integer> second = Pipeline.builder(registry, "shared", Integer.class).circuitBreaker("tiny").build();

        assertThatThrownBy(() -> first.execute(() -> {
            throw new IllegalStateException();
        })).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> second.execute(() -> {
            throw new IllegalStateException();
        })).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> first.execute(() -> "ok")).isInstanceOf(CallNotPermittedException.class);

        assertThatIllegalStateException()
                .isThrownBy(() -> Pipeline.builder(registry, "shared").circuitBreaker().build())
                .withMessageContaining("two different configurations");
    }

    @Test
    void misconfigurationsFailEarly() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Pipeline.builder(registry, "unknown").retry("fiev").build())
                .withMessageContaining("Unknown retry config 'fiev'");
        assertThatIllegalStateException()
                .isThrownBy(() -> Pipeline.builder(registry, "twice").retry().retry("five"))
                .withMessageContaining("retry of pipeline 'twice' is set twice");
        assertThatIllegalStateException()
                .isThrownBy(() -> Pipeline.builder(registry, "twice").fallback(e -> 1).fallback(e -> 2));
        assertThatIllegalStateException()
                .isThrownBy(() -> Pipeline.builder(registry, "nofallback").fallbackSkipOn(List.of(IOException.class)).build());
        assertThatIllegalArgumentException().isThrownBy(() -> Pipeline.builder(registry, " "));
    }

    @Test
    void aPipelineWithoutPoliciesJustCalls() {
        Pipeline<String> pipeline = Pipeline.builder(registry, "empty", String.class).build();
        IOException down = new IOException();

        assertThat(pipeline.execute(() -> "ok")).isEqualTo("ok");
        assertThatThrownBy(() -> pipeline.execute(failing(new AtomicInteger(), 1, down))).isSameAs(down);
        assertThat(pipeline).hasToString("Pipeline[empty]");
    }

    @Test
    void theSyncSuccessPathDoesNotAllocate() {
        Pipeline<String> pipeline = Pipeline.builder(registry, "allocation", String.class)
                .retry()
                .circuitBreaker()
                .rateLimiter(rl -> rl.permits(1_000_000_000).period(Duration.ofSeconds(1)))
                .timeout(t -> t.timeout(Duration.ofSeconds(10)))
                .bulkhead()
                .fallback(failure -> "fallback")
                .build();
        PipelineCall<String, RuntimeException> call = () -> "ok";
        for (int i = 0; i < 20_000; i++) {
            pipeline.execute(call);
        }

        Allocations.Measurement measured = Allocations.steadyState(() -> {
            for (int i = 0; i < 100_000; i++) {
                pipeline.execute(call);
            }
        });

        assertThat(measured.bytes()).as("bytes allocated by 100k calls (the measurement itself may allocate a little)")
                .isLessThan(Allocations.LIMIT);
    }

    @Test
    void theSyncSuccessPathDoesNotAllocateWithARetryBudget() {
        Pipeline<String> pipeline = Pipeline.builder(registry, "budget-allocation", String.class)
                .retry(r -> r.budgetPercent(20).budgetMinRetriesPerSecond(10))
                .build();
        PipelineCall<String, RuntimeException> call = () -> "ok";
        for (int i = 0; i < 20_000; i++) {
            pipeline.execute(call);
        }

        Allocations.Measurement measured = Allocations.steadyState(() -> {
            for (int i = 0; i < 100_000; i++) {
                pipeline.execute(call);
            }
        });

        assertThat(measured.bytes()).as("bytes allocated by 100k calls (the measurement itself may allocate a little)")
                .isLessThan(Allocations.LIMIT);
        assertThat(registry.snapshot().retries()).filteredOn(r -> r.id().equals("budget-allocation"))
                .singleElement().satisfies(r -> assertThat(r.budget()).isNotNull()
                        .satisfies(b -> assertThat(b.calls()).isEqualTo(20_000 + 100_000L * measured.rounds())));
    }

    /** {@code "busy"} is a failure, {@link IllegalStateException} is ignored, {@code "boom"} makes the classifier throw. */
    private static final Classifier<String> BUSY = (result, failure) -> {
        if (failure instanceof IllegalStateException) {
            return Classifier.Outcome.IGNORE;
        }
        if (failure != null) {
            return Classifier.Outcome.FAILURE;
        }
        if (result.equals("boom")) {
            throw new IllegalArgumentException("classifier failed");
        }
        return result.equals("busy") ? Classifier.Outcome.FAILURE : Classifier.Outcome.SUCCESS;
    };

    @Test
    void aClassifiedRetryRetriesRejectedResultsAndReturnsTheLastOne() {
        Pipeline<String> pipeline = Pipeline.builder(registry, "classified-retry", String.class)
                .retry().classifier(BUSY).build();
        AtomicInteger calls = new AtomicInteger();

        assertThat(pipeline.execute(() -> calls.incrementAndGet() < 3 ? "busy" : "ok")).isEqualTo("ok");
        assertThat(calls).hasValue(3);
        calls.set(0);
        assertThat(pipeline.execute(() -> {
            calls.incrementAndGet();
            return "busy";
        })).as("no retries left: the last result").isEqualTo("busy");
        assertThat(calls).hasValue(3);
        calls.set(0);
        assertThatThrownBy(() -> pipeline.execute(() -> {
            calls.incrementAndGet();
            throw new IllegalStateException("ignored");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(calls).as("IGNORE is not retried").hasValue(1);
    }

    @Test
    void aThrowingClassifierEndsTheRetriesThroughTheFallback() {
        AtomicInteger attempts = new AtomicInteger();
        Pipeline<String> pipeline = Pipeline.builder(registry, "classifier-throws", String.class)
                .retry()
                .retryClassifier(BUSY)
                .fallbackHandler(context -> {
                    attempts.set(context.attempts());
                    return context.failure().getMessage();
                })
                .build();
        AtomicInteger calls = new AtomicInteger();

        assertThat(pipeline.execute(() -> calls.incrementAndGet() == 1 ? "busy" : "boom")).isEqualTo("classifier failed");
        assertThat(attempts).hasValue(2);
    }

    @Test
    void aClassifiedBreakerRecordsRejectedResultsAndIgnoresWhatTheClassifierIgnores() {
        Pipeline<String> pipeline = Pipeline.builder(registry, "classified-breaker", String.class)
                .circuitBreaker("tiny")
                .circuitBreakerClassifier(BUSY)
                .build();
        CircuitBreakerHandle handle = registry.circuitBreaker("classified-breaker");

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> pipeline.execute(() -> {
                throw new IllegalStateException();
            })).isInstanceOf(IllegalStateException.class);
        }
        assertThat(handle.state()).as("ignored").isEqualTo(CircuitBreakerState.CLOSED);
        assertThatThrownBy(() -> pipeline.execute(() -> "boom")).isInstanceOf(IllegalArgumentException.class);
        assertThat(pipeline.execute(() -> "busy")).isEqualTo("busy");
        assertThat(handle.state()).as("a throwing classifier and a busy result are two failures")
                .isEqualTo(CircuitBreakerState.OPEN);
    }

    @Test
    void classifiersNeedTheirPolicy() {
        assertThatIllegalStateException()
                .isThrownBy(() -> Pipeline.builder(registry, "no-policy", String.class).classifier(BUSY).build());
        assertThatIllegalStateException()
                .isThrownBy(() -> Pipeline.builder(registry, "no-retry", String.class)
                        .circuitBreaker().retryClassifier(BUSY).build());
        assertThatIllegalStateException()
                .isThrownBy(() -> Pipeline.builder(registry, "no-breaker", String.class)
                        .retry().circuitBreakerClassifier(BUSY).build());
    }

    @Test
    void asyncClassifiersRetryAndRecordResults() {
        Pipeline<String> pipeline = Pipeline.builder(registry, "async-classified", String.class)
                .classifier(BUSY)
                .retry()
                .circuitBreaker("tiny")
                .build();
        AtomicInteger calls = new AtomicInteger();

        CompletableFuture<String> busy = pipeline.executeAsync(() -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture("busy");
        });

        assertThat(busy.handle((value, failure) -> failure).orTimeout(5, TimeUnit.SECONDS).join())
                .as("two busy results open the breaker, which rejects the third attempt and stops the retries")
                .isInstanceOf(CallNotPermittedException.class);
        assertThat(calls).hasValue(2);
        assertThat(registry.circuitBreaker("async-classified").state()).isEqualTo(CircuitBreakerState.OPEN);
    }

    @Test
    void asyncCallsRetryAndFallBackWithTheAttempts() {
        AtomicInteger attempts = new AtomicInteger();
        Pipeline<String> pipeline = Pipeline.builder(registry, "async-fallback", String.class)
                .retry()
                .circuitBreaker()
                .rateLimiter(rl -> rl.permits(1000))
                .timeout(t -> t.timeout(Duration.ofSeconds(10)))
                .bulkhead()
                .fallbackHandler(context -> {
                    attempts.set(context.attempts());
                    return "fallback";
                })
                .build();
        AtomicInteger calls = new AtomicInteger();

        CompletableFuture<String> result = pipeline.executeAsync(() -> {
            calls.incrementAndGet();
            return CompletableFuture.failedFuture(new IOException());
        });

        assertThat(result.orTimeout(5, TimeUnit.SECONDS).join()).isEqualTo("fallback");
        assertThat(calls).hasValue(3);
        assertThat(attempts).hasValue(3);
        assertThat(pipeline.executeAsync(() -> CompletableFuture.completedFuture("ok")).join()).isEqualTo("ok");
    }

    @Test
    void asyncRejectionsAndTimeoutsFailTheFuture() {
        Pipeline<String> breaker = Pipeline.builder(registry, "async-breaker", String.class).circuitBreaker().build();
        registry.circuitBreaker("async-breaker").forceOpen();
        Pipeline<String> limited = Pipeline.builder(registry, "async-limited", String.class)
                .rateLimiter(rl -> rl.permits(1).period(Duration.ofHours(1))).build();
        Pipeline<String> timed = Pipeline.builder(registry, "async-timed", String.class)
                .timeout(t -> t.timeout(Duration.ofMillis(20))).build();

        assertThat(failureOf(breaker.executeAsync(() -> CompletableFuture.completedFuture("ok"))))
                .isInstanceOf(CallNotPermittedException.class);
        assertThat(limited.executeAsync(() -> CompletableFuture.completedFuture("ok")).join()).isEqualTo("ok");
        assertThat(failureOf(limited.executeAsync(() -> CompletableFuture.completedFuture("ok"))))
                .isInstanceOf(RateLimitExceededException.class);
        CompletableFuture<String> late = timed.executeAsync(CompletableFuture::new);
        assertThat(late.handle((value, failure) -> failure).orTimeout(5, TimeUnit.SECONDS).join())
                .isInstanceOf(CallTimeoutException.class);
    }

    @Test
    void anAsyncPipelineWithoutPoliciesTurnsThrowsAndNullsIntoFailures() {
        Pipeline<String> pipeline = Pipeline.builder(registry, "async-empty", String.class).build();
        CompletableFuture<String> own = new CompletableFuture<>();

        assertThat(pipeline.executeAsync(() -> own)).isSameAs(own);
        assertThat(failureOf(pipeline.executeAsync(() -> {
            throw new IOException("thrown");
        }))).isInstanceOf(IOException.class);
        assertThat(failureOf(pipeline.executeAsync(() -> null))).isInstanceOf(NullPointerException.class);
        CompletionStage<String> stage = CompletableFuture.completedFuture("ok").minimalCompletionStage();
        assertThat(pipeline.executeAsync(() -> stage).join()).isEqualTo("ok");
    }
}
