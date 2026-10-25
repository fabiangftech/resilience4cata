package dev.resilience4cata.examples.docs.quickstart;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.CircuitBreakerSnapshot;
import dev.resilience4cata.CircuitBreakerState;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.ResilienceSnapshot;
import dev.resilience4cata.Resilient;
import dev.resilience4cata.event.CircuitBreakerEvent;
import dev.resilience4cata.event.RetryEvent;
import dev.resilience4cata.testkit.ManualClock;
import dev.resilience4cata.testkit.ResilienceTesting;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.LongAdder;
import org.junit.jupiter.api.Test;

/** Runs the quickstart program and checks what the page says it prints, plus the "next steps" it links to. */
class QuickstartTest {

    private static final System.Logger LOG = System.getLogger("shop.resilience");

    @Test
    void theProgramPrintsWhatThePageSays() throws IOException {
        PrintStream original = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try {
            Main.main(new String[0]);
        } finally {
            System.setOut(original);
        }
        assertThat(out.toString(StandardCharsets.UTF_8).lines()).containsExactly("9.99", "0", "CLOSED");
    }

    @Test
    void theBreakerSawFiveCallsAndFourFailures() throws IOException {
        ResilienceRegistry registry = ResilienceRegistry.builder().build();
        PriceClient prices = Resilient.of(PriceClient.class, new FlakyPriceClient(), registry);
        PriceClient down = Resilient.of(PriceClient.class, sku -> {
            throw new IOException("service down");
        }, registry);

        assertThat(prices.price("sku-1")).isEqualByComparingTo("9.99");
        assertThat(down.price("sku-1")).isEqualByComparingTo("0");

        CircuitBreakerSnapshot breaker = registry.circuitBreaker("prices").snapshot();
        assertThat(breaker.bufferedCalls()).isEqualTo(5);
        assertThat(breaker.failedCalls()).isEqualTo(4);
        assertThat(breaker.failureRate()).as("not judged before minimumCalls").isEqualTo(-1f);
        assertThat(registry.circuitBreaker("prices").config().minimumCalls()).isEqualTo(100);
    }

    @Test
    void theGeneratedClassSitsNextToTheInterface() throws ClassNotFoundException {
        Class<?> generated = Class.forName(PriceClient.class.getName() + "$Resilient");
        assertThat(generated.getInterfaces()).containsExactly(PriceClient.class);
        assertThat(generated.getPackageName()).isEqualTo(PriceClient.class.getPackageName());
    }

    @Test
    void eventsAndSnapshotsSeeEveryAttempt() throws IOException {
        LongAdder retries = new LongAdder();
        ResilienceRegistry registry = ResilienceRegistry.builder()
                .listen(CircuitBreakerEvent.StateTransition.class,
                        t -> LOG.log(Level.WARNING, "{0}: {1} -> {2}", t.instance(), t.from(), t.to()))
                .listen(RetryEvent.Retrying.class, e -> retries.increment())
                .build();
        PriceClient prices = Resilient.of(PriceClient.class, new FlakyPriceClient(), registry);
        prices.price("sku-1");   // the first attempt fails, the second succeeds

        CircuitBreakerSnapshot breaker = registry.circuitBreaker("prices").snapshot();
        ResilienceSnapshot all = registry.snapshot();

        assertThat(retries.sum()).isEqualTo(1);
        assertThat(breaker.state()).isEqualTo(CircuitBreakerState.CLOSED);
        assertThat(breaker.bufferedCalls()).isEqualTo(2);
        assertThat(breaker.failedCalls()).isEqualTo(1);
        assertThat(all.circuitBreakers()).extracting(CircuitBreakerSnapshot::instance).containsExactly("prices");
    }

    @Test
    void aManualClockRemovesTheRealSleeps() throws IOException {
        long started = System.nanoTime();

        ManualClock clock = new ManualClock();
        ResilienceRegistry registry = ResilienceTesting.registry(clock).build();   // also seeds the jitter
        PriceClient prices = Resilient.of(PriceClient.class, new FlakyPriceClient(), registry);

        assertThat(prices.price("sku-1")).isEqualByComparingTo("9.99");   // one retry, no real sleep
        assertThat(clock.elapsed()).isBetween(Duration.ZERO, Duration.ofMillis(100));
        assertThat(System.nanoTime() - started).isLessThan(Duration.ofSeconds(1).toNanos());
    }
}
