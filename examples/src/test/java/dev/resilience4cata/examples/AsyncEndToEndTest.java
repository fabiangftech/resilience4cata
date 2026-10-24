package dev.resilience4cata.examples;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.resilience4cata.BulkheadFullException;
import dev.resilience4cata.CallNotPermittedException;
import dev.resilience4cata.CallTimeoutException;
import dev.resilience4cata.CircuitBreakerState;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AsyncEndToEndTest {

    private static final String PREFIX = "dev.resilience4cata.examples.StatusClient#";

    private final ResilienceRegistry registry = ResilienceRegistry.builder().build();
    private final AtomicInteger statusCalls = new AtomicInteger();
    private final List<String> statusThreads = new CopyOnWriteArrayList<>();
    private final CompletableFuture<String> neverCompletes = new CompletableFuture<>();
    private final CompletableFuture<String> held = new CompletableFuture<>();
    private volatile RuntimeException withFallbackFailure = new IllegalStateException("down");
    private volatile int everythingFailures;

    private final StatusClient client = Resilient.of(StatusClient.class, new StatusClient() {
        @Override
        public CompletableFuture<String> status(String id) throws IOException {
            statusThreads.add(Thread.currentThread().isVirtual() ? "virtual" : Thread.currentThread().getName());
            int call = statusCalls.incrementAndGet();
            if (call == 1) {
                throw new IOException("thrown before returning a stage");
            }
            if (call == 2) {
                return CompletableFuture.failedFuture(new IOException("failed stage"));
            }
            return CompletableFuture.supplyAsync(() -> "up " + id);
        }

        @Override
        public CompletionStage<String> ping(String id) {
            return CompletableFuture.failedFuture(new IllegalStateException("unreachable"));
        }

        @Override
        public CompletableFuture<String> slow(String id) {
            return neverCompletes;
        }

        @Override
        public CompletableFuture<String> limited(String id) {
            return id.equals("hold") ? held : CompletableFuture.completedFuture("limited " + id);
        }

        @Override
        public CompletableFuture<String> paced(String id) {
            return CompletableFuture.completedFuture("paced " + id);
        }

        @Override
        public CompletableFuture<String> withFallback(String id) {
            return CompletableFuture.failedFuture(withFallbackFailure);
        }

        @Override
        public CompletionStage<Integer> count(String id) {
            return new CompletableFuture<>();
        }

        @Override
        public CompletableFuture<Integer> everything(String id) throws IOException {
            if (everythingFailures-- > 0) {
                throw new IOException("flaky");
            }
            return CompletableFuture.completedFuture(42);
        }
    }, registry);

    @Test
    void retriesWithoutBlockingAndTreatsAThrowAsAFailedStage() throws Exception {
        CompletableFuture<String> status = client.status("db");
        assertThat(status.get(5, TimeUnit.SECONDS)).isEqualTo("up db");
        assertThat(statusCalls).hasValue(3);
        // The first attempt runs on the caller's thread; retries start on new virtual threads after the delay.
        assertThat(statusThreads).hasSize(3);
        assertThat(statusThreads.get(0)).isEqualTo(Thread.currentThread().getName());
        assertThat(statusThreads.subList(1, 3)).containsOnly("virtual");
    }

    @Test
    void theBreakerRecordsCompletedStagesAndRejectsWithAFailedStage() {
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> client.ping("x").toCompletableFuture().join())
                    .hasCauseInstanceOf(IllegalStateException.class);
        }
        assertThat(registry.circuitBreaker(PREFIX + "ping(java.lang.String)").state()).isEqualTo(CircuitBreakerState.OPEN);

        CompletionStage<String> rejected = client.ping("x");
        assertThatThrownBy(() -> rejected.toCompletableFuture().join()).hasCauseInstanceOf(CallNotPermittedException.class);
    }

    @Test
    void aTimeoutFailsTheFutureAndCancelsTheLateStage() {
        long start = System.nanoTime();
        CompletableFuture<String> slow = client.slow("x");
        assertThatThrownBy(() -> slow.get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .cause().isInstanceOf(CallTimeoutException.class)
                .hasMessageContaining("StatusClient#slow");
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isBetween(90L, 3_000L);
        assertThat(neverCompletes).isCancelled();
    }

    @Test
    void theBulkheadHoldsTheSlotUntilTheStageCompletes() throws Exception {
        CompletableFuture<String> first = client.limited("hold");
        assertThat(registry.bulkhead(PREFIX + "limited(java.lang.String)").concurrentCalls()).isEqualTo(1);
        assertThatThrownBy(() -> client.limited("second").join()).hasCauseInstanceOf(BulkheadFullException.class);

        held.complete("done");
        assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo("done");
        assertThat(registry.bulkhead(PREFIX + "limited(java.lang.String)").concurrentCalls()).isZero();
        assertThat(client.limited("third").join()).isEqualTo("limited third");
    }

    @Test
    void theRateLimiterDelaysAReservedCallWithoutBlockingTheCaller() throws Exception {
        assertThat(client.paced("a").join()).isEqualTo("paced a");

        long start = System.nanoTime();
        CompletableFuture<String> second = client.paced("b");
        long returnedAfter = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo("paced b");
        long completedAfter = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(returnedAfter).as("the caller is not blocked").isLessThan(100);
        assertThat(completedAfter).as("the call starts when its permit is due").isGreaterThanOrEqualTo(100);
    }

    @Test
    void siblingFallbacksMayReturnAValueOrAStage() {
        assertThat(client.withFallback("a").join()).isEqualTo("async fallback a");

        withFallbackFailure = new java.io.UncheckedIOException(new IOException("io"));
        assertThatThrownBy(() -> client.withFallback("b").join())
                .as("no fallback accepts UncheckedIOException: the failure is kept")
                .hasCauseInstanceOf(java.io.UncheckedIOException.class);
    }

    @Test
    void aHandlerReceivesTheTimeoutAndTheAttempts() throws Exception {
        assertThat(client.count("x").toCompletableFuture().get(5, TimeUnit.SECONDS)).isEqualTo(-1);
        assertThat(StatusClient.Unknown.attempts).isEqualTo(1);

        everythingFailures = 5;
        assertThat(client.everything("x").get(5, TimeUnit.SECONDS)).isEqualTo(-1);
        assertThat(StatusClient.Unknown.attempts).isEqualTo(2);

        everythingFailures = 1;
        assertThat(client.everything("x").get(5, TimeUnit.SECONDS)).isEqualTo(42);
        assertThat(registry.bulkhead(PREFIX + "everything(java.lang.String)").concurrentCalls()).isZero();
    }
}
