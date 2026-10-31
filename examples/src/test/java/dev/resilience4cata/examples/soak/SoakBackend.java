package dev.resilience4cata.examples.soak;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * The service behind {@link SoakClient}: values at once or after a few milliseconds, failures, calls too slow for
 * the timeout, and calls that never end unless cancelled. One second in four is a storm in which most calls fail,
 * so the breakers open and close again all along. {@link #inside} counts calls that started and have not ended
 * (returned, failed or been cancelled): it must be back at 0 once the load stops.
 */
final class SoakBackend implements SoakClient {

    private static final long STORM_PERIOD = Duration.ofSeconds(1).toNanos();

    final AtomicInteger inside = new AtomicInteger();

    private static boolean storm() {
        return System.nanoTime() / STORM_PERIOD % 4 == 0;
    }

    /** 0..9; during a storm, mostly 7 (a failure). */
    private static int pick() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return storm() && random.nextInt(10) < 8 ? 7 : random.nextInt(10);
    }

    @Override
    public String sync(int i) throws IOException {
        inside.incrementAndGet();
        try {
            return switch (pick()) {
                case 6 -> sleep(1, "soon:" + i);
                case 7 -> throw new IOException("down");
                case 8 -> sleep(40, "late:" + i);
                default -> "now:" + i;
            };
        } finally {
            inside.decrementAndGet();
        }
    }

    @Override
    public String adaptive(int i) throws IOException {
        return sync(i);
    }

    private static String sleep(long millis, String value) throws InterruptedIOException {
        try {
            Thread.sleep(millis);
            return value;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted");
        }
    }

    @Override
    public CompletableFuture<String> async(int i) {
        inside.incrementAndGet();
        CompletableFuture<String> future = switch (pick()) {
            case 5 -> later(1 + i % 3, "soon:" + i);
            case 6 -> new CompletableFuture<>(); // never, unless cancelled
            case 7 -> CompletableFuture.failedFuture(new IOException("down"));
            case 8 -> later(40, "late:" + i);
            default -> CompletableFuture.completedFuture("now:" + i);
        };
        future.whenComplete((value, failure) -> inside.decrementAndGet());
        return future;
    }

    @Override
    public CompletableFuture<String> hedged(int i) {
        return async(i);
    }

    private static CompletableFuture<String> later(long millis, String value) {
        CompletableFuture<String> future = new CompletableFuture<>();
        CompletableFuture.delayedExecutor(millis, TimeUnit.MILLISECONDS).execute(() -> future.complete(value));
        return future;
    }

    @Override
    public Mono<String> mono(int i) {
        return Mono.defer(() -> {
            inside.incrementAndGet();
            Mono<String> outcome = switch (pick()) {
                case 5 -> Mono.just("soon:" + i).delayElement(Duration.ofMillis(1 + i % 3L), Schedulers.parallel());
                case 6 -> Mono.never();
                case 7 -> Mono.error(new IOException("down"));
                case 8 -> Mono.just("late:" + i).delayElement(Duration.ofMillis(40), Schedulers.parallel());
                default -> Mono.just("now:" + i);
            };
            return outcome.doFinally(signal -> inside.decrementAndGet());
        });
    }

    @Override
    public Flux<String> flux(int i) {
        return Flux.defer(() -> {
            inside.incrementAndGet();
            Flux<String> ticks = Flux.interval(Duration.ofMillis(1), Schedulers.parallel()).map(n -> i + ":" + n);
            Flux<String> outcome = switch (pick()) {
                case 5 -> ticks.take(5);
                case 6 -> ticks.take(1).concatWith(Flux.never()); // stalls after its first element
                case 7 -> Flux.error(new IOException("down"));
                case 8 -> ticks.take(2).concatWith(Flux.error(new IOException("broke"))); // after the commit
                case 9 -> Flux.never();
                default -> Flux.just(i + ":a", i + ":b", i + ":c");
            };
            return outcome.doFinally(signal -> inside.decrementAndGet());
        });
    }
}
