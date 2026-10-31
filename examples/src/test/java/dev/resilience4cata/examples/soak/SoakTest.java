package dev.resilience4cata.examples.soak;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.management.HotSpotDiagnosticMXBean;
import dev.resilience4cata.AdaptiveLimitSnapshot;
import dev.resilience4cata.BulkheadFullException;
import dev.resilience4cata.BulkheadSnapshot;
import dev.resilience4cata.CallNotPermittedException;
import dev.resilience4cata.CallTimeoutException;
import dev.resilience4cata.LimitExceededException;
import dev.resilience4cata.Pipeline;
import dev.resilience4cata.RateLimitExceededException;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.ResilienceSnapshot;
import dev.resilience4cata.Resilient;
import dev.resilience4cata.event.ResilienceEvent;
import dev.resilience4cata.generated.SoakProbe;
import dev.resilience4cata.micrometer.MicrometerMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BooleanSupplier;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;

/**
 * A long run of every policy under load on real clocks and threads, looking for what grows: the heap after a full
 * GC, the library's own threads, the sync-timeout watchdog's per-thread scopes, timer queues, meters and registry
 * instances. Callers are platform threads and short-lived virtual threads (each new thread registers with the
 * watchdog, which must forget it when it dies), and some callers give up early, so cancellation runs all along.
 *
 * <p>Not in {@code check}: {@code ./gradlew :examples:soakTest -Psoak.duration=2h} (default 2 minutes; CI:
 * {@code soak.yml}, weekly). A leak of a few bytes per call is invisible in minutes and obvious in hours.
 *
 * <p>The heap check compares the lowest heap after a full GC in the last quarter of the run with the lowest one
 * between 20% and 45% of it (before that, the JIT, the class loading and the first instances settle), within
 * {@code -Psoak.heapBudgetMb} (default 16). Every report goes to {@code examples/build/soak/report.md}, with a
 * heap dump next to it when the heap grew.
 */
class SoakTest {

    private static final Duration DURATION = parse(System.getProperty("test.soak.duration", "2m"));
    private static final long MB = 1024 * 1024;
    private static final long HEAP_BUDGET = Long.getLong("test.soak.heapBudgetMb", 16) * MB;
    private static final Path REPORT_DIR = Path.of(System.getProperty("test.soak.reportDir", "build/soak"));
    private static final int DRIVERS = Math.max(4, Runtime.getRuntime().availableProcessors());
    private static final int MAX_IN_FLIGHT = 512;
    private static final Duration SETTLE = Duration.ofSeconds(60);
    /** Failures the policies and the backend produce on purpose. */
    private static final Set<Class<? extends Throwable>> EXPECTED = Set.of(IOException.class,
            CallTimeoutException.class, CallNotPermittedException.class, BulkheadFullException.class,
            RateLimitExceededException.class, LimitExceededException.class, CancellationException.class);

    private final Map<String, LongAdder> outcomes = new ConcurrentHashMap<>();
    private final List<Throwable> unexpected = new CopyOnWriteArrayList<>();
    private final List<Throwable> dropped = new CopyOnWriteArrayList<>();
    private final LongAdder calls = new LongAdder();
    private final LongAdder events = new LongAdder();
    private final LongAdder droppedByCallerTimeout = new LongAdder();
    private final Semaphore inFlight = new Semaphore(MAX_IN_FLIGHT);
    private final AtomicInteger callNumbers = new AtomicInteger();

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final MicrometerMetrics metrics = MicrometerMetrics.create(meters);
    private final ResilienceRegistry registry = metrics.register(ResilienceRegistry.builder())
            .listen(ResilienceEvent.class, event -> events.increment())
            .build();
    private final SoakBackend backend = new SoakBackend();
    private final SoakClient client = Resilient.of(SoakClient.class, backend, registry);
    private final Pipeline<String> pipeline = Pipeline.builder(registry, "soak-pipeline", String.class)
            .retry(r -> r.maxAttempts(2).delay(Duration.ofMillis(1)))
            .timeout(t -> t.timeout(Duration.ofMillis(20)))
            .bulkhead(b -> b.maxConcurrent(4))
            .fallback(failure -> "pipeline fallback")
            .fallbackApplyOn(List.of(IOException.class))
            .build();

    record Sample(Duration elapsed, long calls, long heapAfterGc, Set<String> libraryThreads, int liveThreads,
            int watchdogThreads, int queuedTimers, int meters, int instances, int backendInside) {
    }

    @Test
    void nothingGrowsUnderALongLoad() throws Exception {
        Hooks.onErrorDropped(this::dropped);
        metrics.bindTo(registry);
        List<Sample> samples = new ArrayList<>();
        List<String> checks = new ArrayList<>();
        long start = System.nanoTime();
        long deadline = start + DURATION.toNanos();
        long every = Math.clamp(DURATION.toNanos() / 60, Duration.ofSeconds(1).toNanos(),
                Duration.ofSeconds(60).toNanos());
        List<Thread> drivers = new ArrayList<>();
        for (int d = 0; d < DRIVERS; d++) {
            Thread driver = Thread.ofPlatform().name("soak-driver-" + d).daemon().start(() -> drive(deadline));
            drivers.add(driver);
        }
        try {
            while (System.nanoTime() - deadline < 0) {
                TimeUnit.NANOSECONDS.sleep(Math.min(every, deadline - System.nanoTime()));
                samples.add(sample(start));
            }
            for (Thread driver : drivers) {
                driver.join(SETTLE.toMillis());
            }
            boolean ended = inFlight.tryAcquire(MAX_IN_FLIGHT, SETTLE.toSeconds(), TimeUnit.SECONDS);
            check(checks, "every async call ended", ended,
                    (MAX_IN_FLIGHT - inFlight.availablePermits()) + " still running");
            check(checks, "the backend holds no call", settles(() -> backend.inside.get() == 0),
                    backend.inside.get() + " calls inside");
            check(checks, "no timer task is left", settles(() -> SoakProbe.queuedTimers() == 0),
                    SoakProbe.queuedTimers() + " queued");
            check(checks, "the watchdog forgot every dead thread",
                    settles(() -> SoakProbe.watchdogThreads() <= DRIVERS),
                    SoakProbe.watchdogThreads() + " threads registered, at most " + DRIVERS + " alive");
            check(checks, "every bulkhead slot is free", settles(this::bulkheadsAreEmpty),
                    registry.snapshot().bulkheads().toString());
            check(checks, "no adaptive limit holds a call", settles(this::adaptiveLimitsAreEmpty),
                    registry.snapshot().adaptiveLimits().toString());
            Sample last = sample(start);
            samples.add(last);
            checkGrowth(samples, checks);
            check(checks, "no unexpected failure", unexpected.isEmpty(), firstOf(unexpected));
            check(checks, "no error dropped by Reactor", dropped.isEmpty(), firstOf(dropped));
            for (String op : List.of("sync", "adaptive", "pipeline", "async", "hedged", "mono", "flux")) {
                check(checks, op + " answered and failed", count(op, "value") > 0 && failures(op) > 0,
                        outcomesOf(op).toString());
            }
            check(checks, "breakers opened", count("sync", "CallNotPermittedException") > 0
                    && count("async", "CallNotPermittedException") > 0, "no rejection by an open breaker");
        } finally {
            Hooks.resetOnErrorDropped();
            metrics.close();
            writeReport(samples, checks);
        }
        assertThat(checks).as("see " + REPORT_DIR.resolve("report.md")).allMatch(line -> line.startsWith("- ✅"));
    }

    // ---- load

    private void drive(long deadline) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        while (System.nanoTime() - deadline < 0) {
            int i = callNumbers.incrementAndGet();
            boolean virtual = random.nextBoolean();
            switch (random.nextInt(7)) {
                case 0 -> blocking("sync", () -> client.sync(i), virtual);
                case 1 -> blocking("adaptive", () -> client.adaptive(i), virtual);
                case 2 -> blocking("pipeline", () -> pipeline.execute(() -> backend.sync(i)), virtual);
                case 3 -> future("async", () -> client.async(i));
                case 4 -> future("hedged", () -> client.hedged(i));
                case 5 -> reactive("mono", () -> client.mono(i)
                        .timeout(Duration.ofMillis(random.nextInt(5, 60)), Mono.just("caller gave up")).flux());
                default -> reactive("flux", () -> client.flux(i).take(random.nextInt(1, 8))
                        .timeout(Duration.ofMillis(random.nextInt(5, 60)), Flux.empty()));
            }
        }
    }

    interface BlockingCall {
        String call() throws Exception;
    }

    /** On the driver, or on a new virtual thread that the watchdog then has to forget. */
    private void blocking(String op, BlockingCall call, boolean onNewVirtualThread) {
        Runnable run = () -> {
            try {
                call.call();
                record(op, null);
            } catch (Exception e) {
                record(op, e);
            }
        };
        if (!onNewVirtualThread) {
            run.run();
            return;
        }
        try {
            Thread.ofVirtual().name("soak-call").start(run).join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Some callers cancel after a few milliseconds. */
    private void future(String op, java.util.function.Supplier<CompletableFuture<String>> call) {
        acquire();
        CompletableFuture<String> future = call.get();
        future.whenComplete((value, failure) -> {
            record(op, failure);
            inFlight.release();
        });
        ThreadLocalRandom random = ThreadLocalRandom.current();
        if (random.nextInt(10) == 0) {
            CompletableFuture.delayedExecutor(random.nextInt(6), TimeUnit.MILLISECONDS)
                    .execute(() -> future.cancel(true));
        }
    }

    /** The caller's own timeout or {@code take} cancels the call now and then. */
    private void reactive(String op, java.util.function.Supplier<Flux<String>> call) {
        acquire();
        AtomicInteger values = new AtomicInteger();
        call.get().subscribe(value -> values.incrementAndGet(), failure -> {
            record(op, failure);
            inFlight.release();
        }, () -> {
            record(op, values.get() > 0 ? null : new NothingReceived());
            inFlight.release();
        });
    }

    /** A stream that ended without an element: the caller gave up on a {@code Flux} before its first one. */
    static final class NothingReceived extends RuntimeException {
        private static final long serialVersionUID = 1;

        NothingReceived() {
            super(null, null, false, false);
        }
    }

    /**
     * The caller's own {@code timeout} drops an error that races its cancel: it has fired, cancelled the call, and
     * the call's end was already on its way (the Reactive Streams rules allow signals after a cancel). Any other
     * dropped error is a bug; the operator that dropped it is on the recorded stack.
     */
    private void dropped(Throwable error) {
        StackTraceElement[] stack = new Throwable().getStackTrace();
        for (int s = 0; s + 1 < stack.length; s++) {
            if (stack[s].getClassName().equals("reactor.core.publisher.Operators")
                    && stack[s].getMethodName().equals("onErrorDropped")) {
                if (stack[s + 1].getClassName().startsWith("reactor.core.publisher.FluxTimeout$")) {
                    droppedByCallerTimeout.increment();
                    return;
                }
                break;
            }
        }
        if (dropped.size() < 20) {
            dropped.add(new IllegalStateException("dropped: " + error, error));
        }
    }

    private void acquire() {
        try {
            inFlight.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private void record(String op, Throwable failure) {
        calls.increment();
        Throwable cause = unwrap(failure);
        String outcome;
        if (cause == null) {
            outcome = "value";
        } else if (cause instanceof NothingReceived) {
            outcome = "empty";
        } else if (EXPECTED.stream().anyMatch(type -> type.isInstance(cause))) {
            outcome = cause.getClass().getSimpleName();
        } else {
            outcome = "unexpected";
            if (unexpected.size() < 20) {
                unexpected.add(cause);
            }
        }
        outcomes.computeIfAbsent(op + " " + outcome, k -> new LongAdder()).increment();
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    // ---- measurements

    private Sample sample(long start) {
        System.gc();
        System.gc();
        long heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
        Set<String> library = Thread.getAllStackTraces().keySet().stream().map(Thread::getName)
                .filter(name -> name.startsWith("resilience4cata")).collect(Collectors.toCollection(TreeSet::new));
        ResilienceSnapshot snapshot = registry.snapshot();
        int instances = snapshot.circuitBreakers().size() + snapshot.retries().size()
                + snapshot.rateLimiters().size() + snapshot.timeouts().size() + snapshot.bulkheads().size()
                + snapshot.hedges().size() + snapshot.adaptiveLimits().size();
        return new Sample(Duration.ofNanos(System.nanoTime() - start), calls.sum(), heap, library,
                ManagementFactory.getThreadMXBean().getThreadCount(), SoakProbe.watchdogThreads(),
                SoakProbe.queuedTimers(), meters.getMeters().size(), instances, backend.inside.get());
    }

    private void checkGrowth(List<Sample> samples, List<String> checks) throws IOException {
        // the last sample is taken after the load: leave it out of the windows
        List<Sample> loaded = samples.subList(0, samples.size() - 1);
        long total = DURATION.toNanos();
        List<Sample> baseline = between(loaded, total / 5, total * 9 / 20);
        List<Sample> end = between(loaded, total * 3 / 4, Long.MAX_VALUE);
        if (baseline.isEmpty() || end.isEmpty()) {
            check(checks, "enough samples", false, samples.size() + " samples");
            return;
        }
        long base = lowest(baseline, Sample::heapAfterGc);
        long last = lowest(end, Sample::heapAfterGc);
        boolean heapOk = last - base <= HEAP_BUDGET;
        String heapDetail = String.format(Locale.ROOT,
                "%.1f MB → %.1f MB, budget +%d MB", base / (double) MB, last / (double) MB, HEAP_BUDGET / MB);
        check(checks, "the heap after GC did not grow (" + heapDetail + ")", heapOk, "");
        if (!heapOk) {
            dumpHeap();
        }
        Sample warm = baseline.getFirst();
        List<Sample> after = between(samples, total / 5, Long.MAX_VALUE);
        check(checks, "no new library thread", after.stream().allMatch(s -> s.libraryThreads().equals(
                warm.libraryThreads())), warm.libraryThreads() + ", then " + after.stream().map(Sample::libraryThreads)
                        .filter(t -> !t.equals(warm.libraryThreads())).findFirst().orElse(Set.of()));
        check(checks, "no new registry instance", after.stream().allMatch(s -> s.instances() == warm.instances()),
                warm.instances() + " instances, at most " + highest(after, Sample::instances));
        // A counter appears with the first event of its kind (a rare rejection can come late), so the count may still
        // rise; what must hold is that every meter belongs to an instance, whose number is fixed, and its other tags
        // are enum values: then the meters are bounded.
        Set<String> ids = instanceIds();
        List<String> strays = meters.getMeters().stream().map(meter -> meter.getId().getTag("instance"))
                .filter(id -> id != null && !ids.contains(id)).distinct().limit(5).toList();
        check(checks, "every meter belongs to a registry instance", strays.isEmpty(), strays.toString());
    }

    private Set<String> instanceIds() {
        ResilienceSnapshot snapshot = registry.snapshot();
        Set<String> ids = new TreeSet<>();
        snapshot.circuitBreakers().forEach(s -> ids.add(s.instance()));
        snapshot.retries().forEach(s -> ids.add(s.instance()));
        snapshot.rateLimiters().forEach(s -> ids.add(s.instance()));
        snapshot.timeouts().forEach(s -> ids.add(s.instance()));
        snapshot.bulkheads().forEach(s -> ids.add(s.instance()));
        snapshot.hedges().forEach(s -> ids.add(s.instance()));
        snapshot.adaptiveLimits().forEach(s -> ids.add(s.instance()));
        return ids;
    }

    private static List<Sample> between(List<Sample> samples, long fromNanos, long toNanos) {
        return samples.stream().filter(s -> s.elapsed().toNanos() >= fromNanos && s.elapsed().toNanos() <= toNanos)
                .toList();
    }

    private static long lowest(List<Sample> samples, ToLongFunction<Sample> value) {
        return samples.stream().mapToLong(value).min().orElseThrow();
    }

    private static long highest(List<Sample> samples, ToLongFunction<Sample> value) {
        return samples.stream().mapToLong(value).max().orElseThrow();
    }

    private boolean bulkheadsAreEmpty() {
        for (BulkheadSnapshot bulkhead : registry.snapshot().bulkheads()) {
            if (bulkhead.concurrentCalls() != 0 || bulkhead.waitingCalls() != 0) {
                return false;
            }
        }
        return true;
    }

    private boolean adaptiveLimitsAreEmpty() {
        for (AdaptiveLimitSnapshot limit : registry.snapshot().adaptiveLimits()) {
            if (limit.inFlight() != 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean settles(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + SETTLE.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline > 0) {
                return false;
            }
            Thread.sleep(50);
        }
        return true;
    }

    private static void check(List<String> checks, String what, boolean ok, String detail) {
        checks.add((ok ? "- ✅ " : "- ❌ ") + what + (ok || detail.isEmpty() ? "" : ": " + detail));
    }

    private long count(String op, String outcome) {
        LongAdder adder = outcomes.get(op + " " + outcome);
        return adder == null ? 0 : adder.sum();
    }

    private long failures(String op) {
        return outcomesOf(op).entrySet().stream().filter(e -> !e.getKey().equals("value"))
                .mapToLong(Map.Entry::getValue).sum();
    }

    private Map<String, Long> outcomesOf(String op) {
        Map<String, Long> result = new TreeMap<>();
        outcomes.forEach((key, adder) -> {
            if (key.startsWith(op + " ")) {
                result.put(key.substring(op.length() + 1), adder.sum());
            }
        });
        return result;
    }

    private static String firstOf(List<Throwable> failures) {
        return failures.isEmpty() ? "" : failures.size() + ", first: " + failures.getFirst();
    }

    private static void dumpHeap() {
        try {
            Path dump = REPORT_DIR.resolve("heap.hprof");
            Files.createDirectories(REPORT_DIR);
            Files.deleteIfExists(dump);
            ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class).dumpHeap(dump.toString(), true);
        } catch (IOException e) {
            System.err.println("no heap dump: " + e);
        }
    }

    // ---- report

    private void writeReport(List<Sample> samples, List<String> checks) throws IOException {
        StringBuilder out = new StringBuilder();
        out.append("# Soak test\n\n");
        out.append(String.format(Locale.ROOT, "%s on Java %s, %d CPUs, max heap %d MB, %d drivers; %,d calls, "
                + "%,d events, %,d errors dropped by a caller's timeout that fired first.%n%n", DURATION, Runtime.version(), Runtime.getRuntime().availableProcessors(),
                Runtime.getRuntime().maxMemory() / MB, DRIVERS, calls.sum(), events.sum(),
                droppedByCallerTimeout.sum()));
        out.append("## Checks\n\n");
        checks.forEach(line -> out.append(line).append('\n'));
        out.append("\n## Outcomes\n\n| Call | Outcomes |\n|---|---|\n");
        for (String op : List.of("sync", "adaptive", "pipeline", "async", "hedged", "mono", "flux")) {
            out.append("| ").append(op).append(" | ").append(outcomesOf(op).entrySet().stream()
                    .map(e -> String.format(Locale.ROOT, "%s %,d", e.getKey(), e.getValue()))
                    .collect(Collectors.joining(", "))).append(" |\n");
        }
        out.append("\n## Samples\n\n| Elapsed | Calls | Heap after GC (MB) | Live threads | Watchdog threads "
                + "| Timer tasks | Meters | Instances | Backend calls inside |\n|---|---|---|---|---|---|---|---|---|\n");
        int step = Math.max(1, samples.size() / 30);
        for (int s = 0; s < samples.size(); s++) {
            if (s % step != 0 && s != samples.size() - 1) {
                continue;
            }
            Sample sample = samples.get(s);
            out.append(String.format(Locale.ROOT, "| %s | %,d | %.1f | %d | %d | %d | %d | %d | %d |%n",
                    sample.elapsed().truncatedTo(java.time.temporal.ChronoUnit.SECONDS), sample.calls(),
                    sample.heapAfterGc() / (double) MB, sample.liveThreads(), sample.watchdogThreads(),
                    sample.queuedTimers(), sample.meters(), sample.instances(), sample.backendInside()));
        }
        if (!samples.isEmpty()) {
            out.append("\nLibrary threads: ").append(samples.getLast().libraryThreads()).append('\n');
        }
        for (Throwable failure : unexpected.isEmpty() ? dropped : unexpected) {
            java.io.StringWriter trace = new java.io.StringWriter();
            failure.printStackTrace(new java.io.PrintWriter(trace));
            out.append("\n```\n").append(trace.toString().lines().limit(40).collect(Collectors.joining("\n")))
                    .append("\n```\n");
            break;
        }
        Files.createDirectories(REPORT_DIR);
        Files.writeString(REPORT_DIR.resolve("report.md"), out);
        System.out.println(out);
    }

    /** {@code 90s}, {@code 10m}, {@code 2h}, or ISO-8601 ({@code PT2H}). */
    static Duration parse(String text) {
        String value = text.strip().toLowerCase(Locale.ROOT);
        if (value.startsWith("pt")) {
            return Duration.parse(value);
        }
        long amount = Long.parseLong(value.substring(0, value.length() - 1));
        return switch (value.charAt(value.length() - 1)) {
            case 's' -> Duration.ofSeconds(amount);
            case 'm' -> Duration.ofMinutes(amount);
            case 'h' -> Duration.ofHours(amount);
            default -> throw new IllegalArgumentException("not a duration: " + text);
        };
    }
}
