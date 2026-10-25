package dev.resilience4cata.examples;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.resilience4cata.CallTimeoutException;
import dev.resilience4cata.Pipeline;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import dev.resilience4cata.RetryConfig;
import dev.resilience4cata.junit.ResilienceTest;
import dev.resilience4cata.testkit.ManualClock;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@code resilience4cata-junit} against real generated code: annotated classes, {@code Pipeline}, async
 * methods, with {@code failOnPendingTasks} on to check that finished calls leave nothing on the clock.
 */
@ResilienceTest(failOnPendingTasks = true)
class JUnitExtensionEndToEndTest {

    @Test
    void annotatedClassesRetryOnTheInjectedClock(ManualClock clock, ResilienceRegistry registry) {
        ClassOrderService service = Resilient.create(ClassOrderService.class, registry, "us", 2);

        assertThat(service.status("1")).isEqualTo("us:1");

        assertThat(service.statusCalls).hasValue(3);
        assertThat(clock.elapsed()).isPositive(); // two jittered delays, slept on the clock
    }

    @Test
    void pipelinesRetryOnTheInjectedClock(ManualClock clock, ResilienceRegistry registry) {
        Pipeline<String> pipeline = Pipeline.<String>builder(registry, "orders")
                .retry(r -> r.maxAttempts(3).delay(Duration.ofSeconds(1)).backoff(RetryConfig.Backoff.FIXED)
                        .jitter(RetryConfig.Jitter.NONE))
                .build();
        AtomicInteger calls = new AtomicInteger();

        assertThat(pipeline.execute(() -> calls.incrementAndGet() < 3 ? fail() : "ok")).isEqualTo("ok");

        assertThat(clock.elapsed()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void anAsyncTimeoutFiresOnTheClockAndLeavesNoTask(ManualClock clock, ResilienceRegistry registry) {
        ClassOrderService service = Resilient.create(ClassOrderService.class, registry);

        CompletableFuture<String> never = service.never().toCompletableFuture();
        clock.advance(Duration.ofMillis(50));

        assertThatThrownBy(never::join).isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(CallTimeoutException.class);
    }

    @Test
    void anAsyncCallThatFinishesInTimeCancelsItsTimeout(ManualClock clock, ResilienceRegistry registry) {
        Pipeline<String> pipeline = Pipeline.<String>builder(registry, "lookup")
                .timeout(t -> t.timeout(Duration.ofSeconds(1)))
                .build();
        CompletableFuture<String> answer = new CompletableFuture<>();

        CompletableFuture<String> result = pipeline.executeAsync(() -> answer);
        assertThat(clock.pendingTasks()).isEqualTo(1); // the timeout
        answer.complete("found");

        assertThat(result.join()).isEqualTo("found");
        assertThat(clock.pendingTasks()).isZero();
    }

    @Test
    void anAsyncBulkheadHandOffLeavesNoTask(ResilienceRegistry registry) {
        CompletableFuture<String> slow = new CompletableFuture<>();
        AsyncBulkheadClient client = Resilient.of(AsyncBulkheadClient.class, new AsyncBulkheadClient() {
            @Override
            public CompletableFuture<String> fetch(String id) {
                return id.equals("slow") ? slow : CompletableFuture.completedFuture(id);
            }

            @Override
            public CompletableFuture<String> fetchBriefly(String id) {
                return fetch(id);
            }
        }, registry);

        CompletableFuture<String> first = client.fetch("slow");
        CompletableFuture<String> queued = client.fetch("next"); // its 5 s wait limit is a task on the clock
        slow.complete("done");

        assertThat(first.join()).isEqualTo("done");
        assertThat(queued.join()).isEqualTo("next"); // handed off on a virtual thread; the wait limit is cancelled
    }

    @Nested
    class InANestedClass {
        @Test
        void theRegistryFollowsTheClock(ManualClock clock, ResilienceRegistry registry) {
            Pipeline<String> pipeline = Pipeline.<String>builder(registry, "nested")
                    .retry(r -> r.maxAttempts(2).delay(Duration.ofSeconds(5)).jitter(RetryConfig.Jitter.NONE))
                    .build();

            assertThatThrownBy(() -> pipeline.execute(JUnitExtensionEndToEndTest::fail))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(clock.elapsed()).isEqualTo(Duration.ofSeconds(5));
        }
    }

    private static String fail() {
        throw new IllegalStateException("down");
    }
}
