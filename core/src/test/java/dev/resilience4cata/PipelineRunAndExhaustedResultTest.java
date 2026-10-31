package dev.resilience4cata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.resilience4cata.event.RetryEvent;
import java.io.IOException;
import java.lang.reflect.UndeclaredThrowableException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** {@link Pipeline#run}, {@link Pipeline.Builder#onExhaustedResult}, asynchronous fallbacks and classifier give-ups. */
class PipelineRunAndExhaustedResultTest {

    private final List<RetryEvent> events = new CopyOnWriteArrayList<>();
    private final ResilienceRegistry registry = ResilienceRegistry.builder()
            .retryDefaults(r -> r.delay(Duration.ZERO).jitter(RetryConfig.Jitter.NONE))
            .listen(RetryEvent.class, events::add)
            .build();

    /** {@code "busy"} is a failure, {@code "boom"} makes the classifier throw, null (from run) is a success. */
    private static final Classifier<String> BUSY = (result, failure) -> {
        if (failure != null) {
            return Classifier.Outcome.FAILURE;
        }
        if ("boom".equals(result)) {
            throw new IllegalArgumentException("classifier failed");
        }
        return "busy".equals(result) ? Classifier.Outcome.FAILURE : Classifier.Outcome.SUCCESS;
    };

    private static Throwable failureOf(CompletableFuture<?> future) {
        return future.handle((value, failure) -> failure).join();
    }

    // run

    @Test
    void runRetriesAVoidCallAndDeclaresItsCheckedException() {
        Pipeline<Void> pipeline = Pipeline.builder(registry, "run", Void.class).retry(r -> r.maxAttempts(3)).build();
        AtomicInteger calls = new AtomicInteger();
        IOException down = new IOException("down");

        assertThatThrownBy(() -> pipeline.run(() -> {
            calls.incrementAndGet();
            throw down;
        })).isSameAs(down);
        assertThat(calls).hasValue(3);

        calls.set(0);
        PipelineRunnable<IOException> flaky = () -> {
            if (calls.incrementAndGet() < 2) {
                throw new IOException("once");
            }
        };
        try {
            pipeline.run(flaky);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        assertThat(calls).hasValue(2);
    }

    @Test
    void runGoesThroughTheFallbackAndDiscardsItsResult() {
        AtomicInteger attempts = new AtomicInteger();
        Pipeline<String> pipeline = Pipeline.builder(registry, "run-fallback", String.class)
                .retry(r -> r.maxAttempts(2))
                .fallbackHandler(context -> {
                    attempts.set(context.attempts());
                    return "ignored";
                })
                .build();

        pipeline.run(() -> {
            throw new IllegalStateException("down");
        });
        assertThat(attempts).hasValue(2);
    }

    @Test
    void runGivesTheClassifierANullResult() {
        List<String> seen = new ArrayList<>();
        Pipeline<String> pipeline = Pipeline.builder(registry, "run-classified", String.class)
                .retry(r -> r.maxAttempts(3))
                .classifier((result, failure) -> {
                    seen.add(String.valueOf(result));
                    return seen.size() < 2 ? Classifier.Outcome.FAILURE : Classifier.Outcome.SUCCESS;
                })
                .build();

        pipeline.run(() -> { });
        assertThat(seen).containsExactly("null", "null");
    }

    @Test
    void runWithEveryPolicyDoesNotAllocateOnSuccess() {
        Pipeline<Void> pipeline = Pipeline.builder(registry, "run-allocation", Void.class)
                .retry()
                .circuitBreaker()
                .rateLimiter(rl -> rl.permits(1_000_000_000).period(Duration.ofSeconds(1)))
                .timeout(t -> t.timeout(Duration.ofSeconds(10)))
                .bulkhead()
                .fallback(failure -> null)
                .build();
        int[] counter = new int[1];
        PipelineRunnable<RuntimeException> call = () -> counter[0]++;
        for (int i = 0; i < 20_000; i++) {
            pipeline.run(call);
        }

        Allocations.Measurement measured = Allocations.steadyState(() -> {
            for (int i = 0; i < 100_000; i++) {
                pipeline.run(call);
            }
        });

        assertThat(measured.bytes()).as("bytes allocated by 100k calls (the measurement itself may allocate a little)")
                .isLessThan(Allocations.LIMIT);
        assertThat(counter[0]).isEqualTo(20_000 + 100_000 * measured.rounds());
    }

    // onExhaustedResult

    @Test
    void throwOnExhaustedResultThrowsTheLastRejectedResult() {
        Pipeline<String> pipeline = Pipeline.builder(registry, "exhausted-throw", String.class)
                .retry(r -> r.maxAttempts(3))
                .classifier(BUSY)
                .onExhaustedResult(Pipeline.ExhaustedResult.THROW)
                .build();
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> pipeline.execute(() -> {
            calls.incrementAndGet();
            return "busy";
        }))
                .isInstanceOfSatisfying(ResultRejectedException.class, e -> {
                    assertThat(e.result()).isEqualTo("busy");
                    assertThat(e.attempts()).isEqualTo(3);
                    assertThat(e.id()).isEqualTo("exhausted-throw");
                });
        assertThat(calls).hasValue(3);
        assertThat(pipeline.execute(() -> calls.incrementAndGet() < 5 ? "busy" : "ok"))
                .as("an accepted result before the retries run out").isEqualTo("ok");
    }

    @Test
    void returnOnExhaustedResultIsTheDefault() {
        Pipeline<String> pipeline = Pipeline.builder(registry, "exhausted-return", String.class)
                .retry(r -> r.maxAttempts(2))
                .retryClassifier(BUSY)
                .onExhaustedResult(Pipeline.ExhaustedResult.RETURN)
                .build();
        assertThat(pipeline.execute(() -> "busy")).isEqualTo("busy");
    }

    @Test
    void aRejectedResultGoesThroughTheFallbackWithTheAttempts() {
        List<Object> seen = new ArrayList<>();
        Pipeline<String> pipeline = Pipeline.builder(registry, "exhausted-fallback", String.class)
                .retry(r -> r.maxAttempts(2))
                .classifier(BUSY)
                .onExhaustedResult(Pipeline.ExhaustedResult.THROW)
                .fallbackHandler(context -> {
                    seen.add(((ResultRejectedException) context.failure()).result());
                    seen.add(context.attempts());
                    return "fallback";
                })
                .build();

        assertThat(pipeline.execute(() -> "busy")).isEqualTo("fallback");
        assertThat(seen).containsExactly("busy", 2);
    }

    @Test
    void asyncThrowOnExhaustedResultFailsTheFutureOrRunsTheFallback() {
        Pipeline<String> throwing = Pipeline.builder(registry, "exhausted-async", String.class)
                .retry(r -> r.maxAttempts(2))
                .classifier(BUSY)
                .onExhaustedResult(Pipeline.ExhaustedResult.THROW)
                .build();
        assertThat(failureOf(throwing.executeAsync(() -> CompletableFuture.completedFuture("busy"))))
                .isInstanceOf(ResultRejectedException.class);

        Pipeline<String> recovering = Pipeline.builder(registry, "exhausted-async-fallback", String.class)
                .retry(r -> r.maxAttempts(2))
                .classifier(BUSY)
                .onExhaustedResult(Pipeline.ExhaustedResult.THROW)
                .fallback(failure -> failure.getClass().getSimpleName())
                .build();
        assertThat(recovering.executeAsync(() -> CompletableFuture.completedFuture("busy")).join())
                .isEqualTo("ResultRejectedException");
    }

    @Test
    void onExhaustedResultNeedsARetryWithAClassifier() {
        assertThatIllegalStateException().isThrownBy(() -> Pipeline.builder(registry, "no-classifier", String.class)
                .retry().onExhaustedResult(Pipeline.ExhaustedResult.THROW).build());
        assertThatIllegalStateException().isThrownBy(() -> Pipeline.builder(registry, "breaker-classifier", String.class)
                .retry().circuitBreaker().circuitBreakerClassifier(BUSY)
                .onExhaustedResult(Pipeline.ExhaustedResult.THROW).build());
        assertThatIllegalStateException().isThrownBy(() -> Pipeline.builder(registry, "twice", String.class)
                .onExhaustedResult(Pipeline.ExhaustedResult.THROW).onExhaustedResult(Pipeline.ExhaustedResult.RETURN));
    }

    // GaveUp when the classifier throws

    @Test
    void aThrowingClassifierPublishesGaveUp() {
        Pipeline<String> pipeline = Pipeline.builder(registry, "classifier-gave-up", String.class)
                .retry(r -> r.maxAttempts(3))
                .classifier(BUSY)
                .build();
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> pipeline.execute(() -> calls.incrementAndGet() == 1 ? "busy" : "boom"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(events).hasSize(2);
        assertThat(events.get(0)).isInstanceOf(RetryEvent.Retrying.class);
        assertThat(events.get(1)).isInstanceOfSatisfying(RetryEvent.GaveUp.class, gaveUp -> {
            assertThat(gaveUp.id()).isEqualTo("classifier-gave-up");
            assertThat(gaveUp.attempts()).isEqualTo(2);
            assertThat(gaveUp.reason()).isEqualTo(RetryEvent.Reason.CLASSIFIER_FAILED);
            assertThat(gaveUp.failure()).isInstanceOf(IllegalArgumentException.class);
        });
    }

    @Test
    void aClassifierThrowingOnAnExceptionPublishesGaveUpToo() {
        Pipeline<String> pipeline = Pipeline.builder(registry, "classifier-gave-up-failure", String.class)
                .retry()
                .classifier((result, failure) -> {
                    throw new IllegalArgumentException("classifier failed");
                })
                .fallback(failure -> "fallback")
                .build();

        assertThat(pipeline.execute(() -> {
            throw new IllegalStateException("down");
        })).isEqualTo("fallback");
        assertThat(events).singleElement().isInstanceOfSatisfying(RetryEvent.GaveUp.class,
                gaveUp -> assertThat(gaveUp.reason()).isEqualTo(RetryEvent.Reason.CLASSIFIER_FAILED));
    }

    @Test
    void anAsyncThrowingClassifierPublishesGaveUp() {
        Pipeline<String> pipeline = Pipeline.builder(registry, "classifier-gave-up-async", String.class)
                .retry()
                .classifier(BUSY)
                .build();

        assertThat(failureOf(pipeline.executeAsync(() -> CompletableFuture.completedFuture("boom"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(events).singleElement().isInstanceOfSatisfying(RetryEvent.GaveUp.class, gaveUp -> {
            assertThat(gaveUp.attempts()).isEqualTo(1);
            assertThat(gaveUp.reason()).isEqualTo(RetryEvent.Reason.CLASSIFIER_FAILED);
        });
    }

    // Asynchronous fallbacks

    @Test
    void anAsyncFallbackReturnsAStage() {
        AtomicInteger attempts = new AtomicInteger();
        Pipeline<String> pipeline = Pipeline.builder(registry, "async-fallback", String.class)
                .retry(r -> r.maxAttempts(2))
                .fallbackAsyncHandler(context -> {
                    attempts.set(context.attempts());
                    return CompletableFuture.supplyAsync(() -> "later: " + context.failure().getMessage());
                })
                .build();

        assertThat(pipeline.executeAsync(() -> CompletableFuture.failedFuture(new IOException("down"))).join())
                .isEqualTo("later: down");
        assertThat(attempts).hasValue(2);
    }

    @Test
    void anAsyncFallbackWithoutRetry() {
        Pipeline<String> pipeline = Pipeline.builder(registry, "async-fallback-alone", String.class)
                .fallbackAsync(failure -> CompletableFuture.completedFuture("fallback"))
                .build();
        assertThat(pipeline.executeAsync(() -> CompletableFuture.failedFuture(new IOException("down"))).join())
                .isEqualTo("fallback");
    }

    @Test
    void aFailedOrNullAsyncFallbackFailsTheCall() {
        IOException fallbackFailure = new IOException("fallback down");
        Pipeline<String> failing = Pipeline.builder(registry, "async-fallback-fails", String.class)
                .fallbackAsync(failure -> CompletableFuture.failedFuture(fallbackFailure))
                .build();
        assertThat(failureOf(failing.executeAsync(() -> CompletableFuture.failedFuture(new IOException("down")))))
                .isSameAs(fallbackFailure);

        Pipeline<String> nulls = Pipeline.builder(registry, "async-fallback-null", String.class)
                .fallbackAsync(failure -> null)
                .build();
        assertThat(failureOf(nulls.executeAsync(() -> CompletableFuture.failedFuture(new IOException("down")))))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("async-fallback-null");
        assertThatThrownBy(() -> nulls.execute(() -> {
            throw new IOException("down");
        })).isInstanceOf(NullPointerException.class).hasMessageContaining("async-fallback-null");
    }

    @Test
    void syncCallsWaitForAnAsyncOnlyFallback() {
        Pipeline<String> pipeline = Pipeline.builder(registry, "async-fallback-sync", String.class)
                .fallbackAsync(failure -> failure.getMessage().equals("rethrow")
                        ? CompletableFuture.failedFuture(failure)
                        : failure.getMessage().equals("checked")
                                ? CompletableFuture.failedFuture(new IOException("other"))
                                : CompletableFuture.supplyAsync(() -> "waited"))
                .build();

        assertThat(pipeline.execute(() -> {
            throw new IllegalStateException("down");
        })).isEqualTo("waited");
        IOException rethrown = new IOException("rethrow");
        assertThatThrownBy(() -> pipeline.execute(() -> {
            throw rethrown;
        })).as("the call's own failure, unwrapped").isSameAs(rethrown);
        assertThatThrownBy(() -> pipeline.execute(() -> {
            throw new IOException("checked");
        })).as("another checked exception").isInstanceOf(UndeclaredThrowableException.class);
    }

    @Test
    void syncAndAsyncFallbacksCanBeCombined() {
        Pipeline<String> pipeline = Pipeline.builder(registry, "both-fallbacks", String.class)
                .fallback(failure -> "sync")
                .fallbackAsync(failure -> CompletableFuture.completedFuture("async"))
                .build();

        assertThat(pipeline.execute(() -> {
            throw new IllegalStateException("down");
        })).isEqualTo("sync");
        assertThat(pipeline.executeAsync(() -> CompletableFuture.failedFuture(new IOException("down"))).join())
                .isEqualTo("async");
    }

    @Test
    void asyncFallbacksAreSetOnceAndCountAsAFallbackForSelection() {
        assertThatIllegalStateException().isThrownBy(() -> Pipeline.builder(registry, "async-twice", String.class)
                .fallbackAsync(failure -> CompletableFuture.completedFuture("a"))
                .fallbackAsync(failure -> CompletableFuture.completedFuture("b")));
        Pipeline<String> selected = Pipeline.builder(registry, "async-selected", String.class)
                .fallbackAsync(failure -> CompletableFuture.completedFuture("fallback"))
                .fallbackApplyOn(List.of(IOException.class))
                .build();
        assertThat(failureOf(selected.executeAsync(() -> CompletableFuture.failedFuture(new IllegalStateException()))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(selected.executeAsync(() -> CompletableFuture.failedFuture(new IOException())).join())
                .isEqualTo("fallback");
    }
}
