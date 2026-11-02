package dev.resilience4cata.benchmark;

import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * A synchronous timeout with one new virtual thread per call, as a server with a virtual thread per request runs
 * it. {@link TimeoutBenchmark} calls from long-lived platform threads, which register with the watchdog once; here
 * every call is the first timed call of its thread, so it pays for the registration: a {@code ThreadScopes} object
 * for the thread and one add to the watchdog's shared queue. The watchdog forgets a dead thread on its next tick.
 * Run with {@code -t 1} and {@code -t 4}.
 *
 * <ul>
 *   <li>{@code baseline}: start a virtual thread that calls the service directly, and join it.</li>
 *   <li>{@code resilience4cata}: the same with the 1 s {@code @Timeout} of {@link TimedService}. The difference
 *       from {@code baseline} is what the timeout adds to a request.</li>
 * </ul>
 *
 * <p>No Resilience4j counterpart: its {@code TimeLimiter} hands a synchronous call to an executor anyway
 * ({@link TimeoutBenchmark}), so a new thread per call changes nothing for it.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgsAppend = {"-Xms1g", "-Xmx1g"})
public class VirtualThreadTimeoutBenchmark {

    private TimedService direct;
    private TimedService resilience4cata;

    /** Builds the service, with and without the timeout. */
    @Setup
    public void setup() {
        direct = new TimedService.Impl();
        resilience4cata = Resilient.of(TimedService.class, new TimedService.Impl(), ResilienceRegistry.builder().build());
    }

    @Benchmark
    public String baseline() throws InterruptedException {
        return onNewVirtualThread(direct);
    }

    @Benchmark
    public String resilience4cata() throws InterruptedException {
        return onNewVirtualThread(resilience4cata);
    }

    private static String onNewVirtualThread(TimedService service) throws InterruptedException {
        String[] result = new String[1];
        Thread thread = Thread.ofVirtual().start(() -> result[0] = service.call());
        thread.join();
        return result[0];
    }
}
