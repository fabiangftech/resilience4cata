package dev.resilience4cata.junit.fixtures;

import dev.resilience4cata.Pipeline;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.testkit.ManualClock;
import java.time.Duration;

/** A retry with the default full jitter, to tell registries with different seeds apart. */
public final class JitteredRetry {

    private JitteredRetry() {
    }

    /**
     * Runs a call that always fails through four attempts with a 1 s jittered delay.
     *
     * @return how far the clock moved: the sum of the three jittered delays
     */
    public static Duration delays(ResilienceRegistry registry, ManualClock clock) {
        Pipeline<String> pipeline = Pipeline.<String>builder(registry, "jittered")
                .retry(r -> r.maxAttempts(4).delay(Duration.ofSeconds(1)))
                .build();
        Duration before = clock.elapsed();
        try {
            pipeline.execute(() -> {
                throw new IllegalStateException("down");
            });
        } catch (IllegalStateException expected) {
            // every attempt failed
        }
        return clock.elapsed().minus(before);
    }
}
