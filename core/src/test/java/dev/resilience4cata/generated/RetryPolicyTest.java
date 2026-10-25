package dev.resilience4cata.generated;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.CallNotPermittedException;
import dev.resilience4cata.RetryConfig;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {

    private final AtomicLong now = new AtomicLong(1_000);
    private final List<Long> sleeps = new ArrayList<>();

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    private RetryPolicy policy(RetryConfig.Builder builder) {
        return new RetryPolicy(builder.build(), now::get, sleeps::add);
    }

    private static RetryConfig.Builder noJitter() {
        return RetryConfig.builder().jitter(RetryConfig.Jitter.NONE);
    }

    @Test
    void retriesUntilMaxAttempts() {
        RetryPolicy retry = policy(noJitter().maxAttempts(3));
        IOException failure = new IOException("boom");

        assertThat(retry.onFailure(1, failure, retry.start())).isGreaterThanOrEqualTo(0);
        assertThat(retry.onFailure(2, failure, retry.start())).isGreaterThanOrEqualTo(0);
        assertThat(retry.onFailure(3, failure, retry.start())).isEqualTo(RetryPolicy.NO_RETRY);
    }

    @Test
    void exponentialBackoffIsCappedByMaxDelay() {
        RetryPolicy retry = policy(noJitter().maxAttempts(10).delay(Duration.ofMillis(100))
                .multiplier(2).maxDelay(Duration.ofMillis(500)));

        assertThat(retry.baseDelay(1)).isEqualTo(Duration.ofMillis(100).toNanos());
        assertThat(retry.baseDelay(2)).isEqualTo(Duration.ofMillis(200).toNanos());
        assertThat(retry.baseDelay(3)).isEqualTo(Duration.ofMillis(400).toNanos());
        assertThat(retry.baseDelay(4)).isEqualTo(Duration.ofMillis(500).toNanos());
        assertThat(retry.baseDelay(60)).isEqualTo(Duration.ofMillis(500).toNanos());
    }

    @Test
    void fixedBackoffKeepsTheSameDelay() {
        RetryPolicy retry = policy(noJitter().backoff(RetryConfig.Backoff.FIXED).delay(Duration.ofMillis(50)));

        assertThat(retry.baseDelay(1)).isEqualTo(retry.baseDelay(5)).isEqualTo(Duration.ofMillis(50).toNanos());
    }

    @Test
    void fullJitterStaysWithinTheComputedDelay() {
        RetryPolicy retry = policy(RetryConfig.builder().jitter(RetryConfig.Jitter.FULL).maxAttempts(1000)
                .backoff(RetryConfig.Backoff.FIXED).delay(Duration.ofMillis(10)));

        for (int attempt = 1; attempt < 1000; attempt++) {
            assertThat(retry.onFailure(attempt, new IOException(), 0)).isBetween(0L, Duration.ofMillis(10).toNanos());
        }
    }

    @Test
    void abortOnWinsOverRetryOn() {
        RetryPolicy retry = policy(noJitter()
                .retryOn(List.of(Exception.class))
                .abortOn(List.of(IllegalArgumentException.class)));

        assertThat(retry.onFailure(1, new IllegalStateException(), 0)).isGreaterThanOrEqualTo(0);
        assertThat(retry.onFailure(1, new IllegalArgumentException(), 0)).isEqualTo(RetryPolicy.NO_RETRY);
    }

    @Test
    void anOpenBreakerIsNeverRetriedWhateverTheListsSay() {
        CallNotPermittedException rejected = new CallNotPermittedException("cb");

        assertThat(RetryConfig.DEFAULTS.abortOn()).as("an implicit rule, not a list entry").isEmpty();
        assertThat(policy(noJitter()).onFailure(1, rejected, 0)).isEqualTo(RetryPolicy.NO_RETRY);
        // A custom abortOn replaces the default list but keeps the implicit abort.
        assertThat(policy(noJitter().abortOn(List.of(IllegalArgumentException.class))).onFailure(1, rejected, 0))
                .isEqualTo(RetryPolicy.NO_RETRY);
        // Even naming it in retryOn does not retry it.
        assertThat(policy(noJitter().retryOn(List.of(CallNotPermittedException.class, RuntimeException.class)))
                .onFailure(1, rejected, 0)).isEqualTo(RetryPolicy.NO_RETRY);
        assertThat(policy(noJitter().abortOn(List.of())).onFailure(1, rejected, 0)).isEqualTo(RetryPolicy.NO_RETRY);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void errorsAreNotRetriedByDefault() {
        RetryPolicy retry = policy(noJitter());

        assertThat(retry.onFailure(1, new StackOverflowError(), 0)).isEqualTo(RetryPolicy.NO_RETRY);
        assertThat(retry.onFailure(1, new UncheckedIOException(new IOException()), 0)).isGreaterThanOrEqualTo(0);
    }

    @Test
    void disabledNeverRetries() {
        RetryPolicy retry = policy(noJitter().enabled(false));

        assertThat(retry.onFailure(1, new IOException(), 0)).isEqualTo(RetryPolicy.NO_RETRY);
    }

    @Test
    void maxDurationStopsRetriesThatWouldExceedTheBudget() {
        RetryPolicy retry = policy(noJitter().maxAttempts(10).backoff(RetryConfig.Backoff.FIXED)
                .delay(Duration.ofMillis(100)).maxDuration(Duration.ofMillis(250)));
        long start = retry.start();
        assertThat(start).isEqualTo(1_000);

        now.addAndGet(Duration.ofMillis(100).toNanos());
        assertThat(retry.onFailure(1, new IOException(), start)).isEqualTo(Duration.ofMillis(100).toNanos());

        now.addAndGet(Duration.ofMillis(100).toNanos());
        assertThat(retry.onFailure(2, new IOException(), start)).isEqualTo(RetryPolicy.NO_RETRY);
    }

    @Test
    void startDoesNotReadTheClockWithoutMaxDuration() {
        RetryPolicy retry = policy(noJitter());

        assertThat(retry.start()).isZero();
    }

    @Test
    void interruptedThreadsAreNotRetried() {
        RetryPolicy retry = policy(noJitter());

        assertThat(retry.onFailure(1, new InterruptedException(), 0)).isEqualTo(RetryPolicy.NO_RETRY);
        Thread.currentThread().interrupt();
        assertThat(retry.onFailure(1, new IOException(), 0)).isEqualTo(RetryPolicy.NO_RETRY);
    }

    @Test
    void pauseSleepsAndReportsInterrupts() {
        RetryPolicy retry = policy(noJitter());
        assertThat(retry.pause(42)).isTrue();
        assertThat(sleeps).containsExactly(42L);

        RetryPolicy interrupted = new RetryPolicy(noJitter().build(), now::get, nanos -> {
            throw new InterruptedException();
        });
        assertThat(interrupted.pause(42)).isFalse();
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    void zeroPauseDoesNotSleep() {
        assertThat(policy(noJitter()).pause(0)).isTrue();
        assertThat(sleeps).isEmpty();
    }
}
