package dev.resilience4cata.micronaut.usability;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.testkit.ManualClock;
import io.micronaut.context.ApplicationContext;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/** Asynchronous methods of a Micronaut bean, on the context's {@code ManualClock}. */
class AsyncBeansTest {

    @Test
    void anAsyncRetryPausesOnTheManualClock() {
        try (ApplicationContext context = ApplicationContext.run(Map.of("test.usability", "async"))) {
            AsyncQuotes quotes = context.getBean(AsyncQuotes.class);
            ManualClock clock = context.getBean(ManualClock.class);
            context.getBean(Failures.class).failNext(2);

            CompletableFuture<String> quote = quotes.quote("a");
            assertThat(quote).as("waiting for the first pause").isNotDone();
            clock.advanceUntilIdle();
            assertThat(quote).isCompletedWithValue("a=1");
            assertThat(context.getBean(Failures.class).calls()).isEqualTo(3);
            assertThat(clock.elapsed()).isPositive();
        }
    }

    @Test
    void anAsyncTimeoutFallsBackOnTheManualClock() {
        try (ApplicationContext context = ApplicationContext.run(Map.of("test.usability", "async"))) {
            AsyncQuotes quotes = context.getBean(AsyncQuotes.class);
            ManualClock clock = context.getBean(ManualClock.class);

            CompletableFuture<String> slow = quotes.slow("b").toCompletableFuture();
            assertThat(slow).isNotDone();
            clock.advance(Duration.ofSeconds(2));
            assertThat(slow).isCompletedWithValue("b=cached");
            assertThat(quotes.never).as("the timed-out stage is cancelled").isCancelled();
        }
    }
}
