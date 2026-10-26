package dev.resilience4cata.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.AdaptiveLimitHandle;
import dev.resilience4cata.LimitExceededException;
import dev.resilience4cata.Pipeline;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.event.AdaptiveLimitEvent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * An adaptive limit on a {@link ManualClock}: latencies are what the clock says, windows end when the clock
 * passes them, so the limit moves deterministically. Asynchronous calls let one thread keep many calls in
 * flight. The adaptive limit schedules nothing, so the clock never has pending tasks.
 */
class AdaptiveLimitOnManualClockTest {

    private static final Duration WINDOW = Duration.ofSeconds(1);

    private final ManualClock clock = new ManualClock();
    private final List<AdaptiveLimitEvent.LimitChanged> changes = new ArrayList<>();
    private final ResilienceRegistry registry = ResilienceTesting.registry(clock)
            .listen(AdaptiveLimitEvent.LimitChanged.class, changes::add)
            .build();
    private final Pipeline<String> pipeline = Pipeline.builder(registry, "search", String.class)
            .adaptiveLimit(a -> a.initialLimit(20).window(WINDOW).minWindowSamples(1))
            .build();
    private final AdaptiveLimitHandle limit = registry.adaptiveLimit("search");
    /** When the current window ends: one window after the pipeline was built, then one after each end. */
    private Duration deadline = WINDOW;
    private Duration now = Duration.ZERO;

    /** Runs as many calls as the limit allows, all in flight together, each taking {@code latency}; returns the limit after. */
    private int window(Duration latency) {
        advanceTo(deadline.minus(latency));
        List<CompletableFuture<String>> stages = new ArrayList<>();
        List<CompletableFuture<String>> results = new ArrayList<>();
        for (int i = limit.limit(); i > 0; i--) {
            CompletableFuture<String> stage = new CompletableFuture<>();
            stages.add(stage);
            results.add(pipeline.executeAsync(() -> stage));
        }
        assertThat(pipeline.executeAsync(() -> CompletableFuture.completedFuture("one too many")))
                .as("the limit is full").failsWithin(Duration.ZERO).withThrowableThat().havingCause()
                .isInstanceOf(LimitExceededException.class);
        advanceTo(deadline);
        stages.forEach(stage -> stage.complete("ok"));
        assertThat(results).allMatch(r -> r.isDone() && !r.isCompletedExceptionally());
        deadline = deadline.plus(WINDOW);
        return limit.limit();
    }

    private void advanceTo(Duration time) {
        clock.advance(time.minus(now));
        now = time;
    }

    @Test
    void theLimitGrowsWhileLatencyHoldsAndShrinksWhenItRises() {
        int limitAfter = 0;
        for (int i = 0; i < 20; i++) {
            limitAfter = window(Duration.ofMillis(20));
        }
        assertThat(limitAfter).as("grows while latency holds").isGreaterThan(30);
        int peak = limitAfter;

        for (int i = 0; i < 15; i++) {
            limitAfter = window(Duration.ofMillis(200));
        }
        assertThat(limitAfter).as("shrinks once latency is ten times the long-term average").isLessThan(peak / 2);
        assertThat(limit.snapshot().windowLatency()).isEqualTo(Duration.ofMillis(200));
        assertThat(limit.inFlight()).isZero();
        assertThat(changes).isNotEmpty().allSatisfy(c -> assertThat(c.instance()).isEqualTo("search"));
        assertThat(clock.pendingTasks()).as("nothing is scheduled").isZero();
    }

    @Test
    void theSameScriptGivesTheSameLimits() {
        assertThat(script()).as("two runs, each on its own clock and registry").isEqualTo(script());
    }

    /** Fifteen windows on a new clock: seven at 30 ms, then 300 ms. Returns the limit after each. */
    private static List<Integer> script() {
        ManualClock time = new ManualClock();
        ResilienceRegistry timed = ResilienceTesting.registry(time).build();
        Pipeline<String> limited = Pipeline.builder(timed, "script", String.class)
                .adaptiveLimit(a -> a.initialLimit(10).window(WINDOW).minWindowSamples(1))
                .build();
        AdaptiveLimitHandle handle = timed.adaptiveLimit("script");
        List<Integer> limits = new ArrayList<>();
        for (int w = 1; w <= 15; w++) {
            Duration latency = Duration.ofMillis(w <= 7 ? 30 : 300);
            time.advance(WINDOW.minus(latency));
            List<CompletableFuture<String>> stages = new ArrayList<>();
            for (int i = handle.limit(); i > 0; i--) {
                CompletableFuture<String> stage = new CompletableFuture<>();
                stages.add(stage);
                limited.executeAsync(() -> stage);
            }
            time.advance(latency);
            stages.forEach(stage -> stage.complete("ok"));
            limits.add(handle.limit());
        }
        return limits;
    }
}
