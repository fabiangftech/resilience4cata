package dev.resilience4cata.processor;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code locked = true}: passed to the registry only when the annotation sets something to lock. */
class LockedProcessorTest {

    @TempDir
    Path workDir;

    @Test
    void passesTheLockOnlyWhenThereAreValuesToLock() {
        Compilation result = Compilation.compile(workDir, Map.of("demo.Client", """
                package demo;
                import dev.resilience4cata.annotation.Bulkhead;
                import dev.resilience4cata.annotation.CircuitBreaker;
                import dev.resilience4cata.annotation.ResiliencePolicy;
                import dev.resilience4cata.annotation.Retry;
                import dev.resilience4cata.annotation.Timeout;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                @Retry(maxAttempts = 2, locked = true)
                public interface Client {
                    @CircuitBreaker(name = "a", minimumCalls = 5, locked = true)
                    String locked();
                    @CircuitBreaker(name = "b", locked = true)
                    String nothingToLock();
                    @Retry(locked = false)
                    @Timeout("2s")
                    String unlocked();
                    @Bundle
                    String bundled();

                    @ResiliencePolicy
                    @Retention(RetentionPolicy.CLASS)
                    @Bulkhead(maxConcurrent = 3, locked = true)
                    @interface Bundle {
                    }
                }
                """));

        assertThat(result.errors()).isEmpty();
        String generated = result.generatedSource("demo.Client$Resilient");
        assertThat(generated)
                .contains("""
                        this.circuitBreaker0 = registry.circuitBreakerPolicy("demo.Client#locked()", "a", "", r4c$b -> {
                                    r4c$b.minimumCalls(5);
                                }, true);""")
                .contains("""
                        this.circuitBreaker1 = registry.circuitBreakerPolicy("demo.Client#nothingToLock()", "b", "", null);""")
                .contains("""
                        this.retry2 = registry.retryPolicy("demo.Client#unlocked()", "", "", r4c$b -> {
                                    r4c$b.maxAttempts(2);
                                });""")
                .contains("""
                        this.timeout2 = registry.timeoutPolicy("demo.Client#unlocked()", "", "", r4c$b -> {
                                    r4c$b.timeout(java.time.Duration.ofNanos(2000000000L));
                                });""")
                .contains("""
                        this.bulkhead3 = registry.bulkheadPolicy("demo.Client#bundled()", "", "", r4c$b -> {
                                    r4c$b.maxConcurrent(3);
                                }, true);""");
        // The type-level lock applies to every method that does not unlock it.
        assertThat(generated).contains("""
                        this.retry0 = registry.retryPolicy("demo.Client#locked()", "", "", r4c$b -> {
                                    r4c$b.maxAttempts(2);
                                }, true);""");
    }
}
