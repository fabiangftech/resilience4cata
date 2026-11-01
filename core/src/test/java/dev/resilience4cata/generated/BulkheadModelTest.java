package dev.resilience4cata.generated;

import dev.resilience4cata.BulkheadConfig;
import dev.resilience4cata.BulkheadSnapshot;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * Drives {@link BulkheadPolicy} through random sequences of synchronous and asynchronous calls that enter,
 * leave, fail, complete at once or later, or are cancelled by their caller, and compares it after every
 * step with a counter of the calls inside: never negative, never above {@code maxConcurrent}, every slot
 * given back exactly once. With {@code maxWait > 0} only calls that find a free slot are made, since a
 * waiting call would wait in real time or on a virtual thread (that path has its own tests). See
 * {@link ModelRuns} to replay a seed.
 */
class BulkheadModelTest {

    private static final int SEEDS = 1500;
    private static final int STEPS = 400;

    @Test
    void matchesTheModel() {
        ModelRuns.forEachSeed(SEEDS, BulkheadModelTest::run);
    }

    private static void run(long seed) {
        SplittableRandom random = new SplittableRandom(seed);
        int max = random.nextInt(1, 8);
        boolean waits = random.nextInt(3) == 0;
        BulkheadConfig config = BulkheadConfig.builder().enabled(random.nextInt(20) != 0).maxConcurrent(max)
                .maxWait(waits ? Duration.ofMillis(random.nextInt(1, 500)) : Duration.ZERO).build();
        BulkheadPolicy bulkhead = new BulkheadPolicy("model", config,
                PolicyEnvironment.forTest(() -> 0L, RetryPolicy.Sleeper.NONE));
        ModelRuns.Trace trace = new ModelRuns.Trace(seed, config.toString());
        boolean enabled = config.enabled();
        int inside = 0; // the model
        int syncInside = 0;
        List<Pending> pending = new ArrayList<>();

        for (int step = 0; step < STEPS; step++) {
            int op = random.nextInt(100);
            boolean free = !enabled || inside < max;
            if (op < 25 && (free || !waits)) {
                boolean entered = bulkhead.tryEnter();
                trace.step("tryEnter -> " + entered);
                trace.equal("tryEnter", free, entered);
                if (entered) {
                    syncInside++;
                    inside += enabled ? 1 : 0;
                }
            } else if (op < 40 && syncInside > 0) {
                trace.step("exit");
                bulkhead.exit();
                syncInside--;
                inside -= enabled ? 1 : 0;
            } else if (op < 70 && (free || !waits)) {
                int shape = random.nextInt(4);
                CompletableFuture<String> stage = new CompletableFuture<>();
                AsyncCall<String> call = switch (shape) {
                    case 0 -> () -> CompletableFuture.completedFuture("done at once");
                    case 1 -> () -> CompletableFuture.failedFuture(new IOException("failed at once"));
                    case 2 -> () -> {
                        throw new IOException("thrown");
                    };
                    default -> () -> stage;
                };
                CompletableFuture<String> result = bulkhead.executeAsync(call);
                boolean rejected = result.isCompletedExceptionally() && rejection(result);
                trace.step("executeAsync (" + List.of("completed", "failed", "throwing", "pending").get(shape)
                        + ") -> " + (rejected ? "rejected" : result.isDone() ? "done" : "pending"));
                trace.equal("rejected", !free, rejected);
                if (!rejected && shape == 3) {
                    trace.check(!result.isDone(), "a pending call's future is done");
                    inside += enabled ? 1 : 0;
                    pending.add(new Pending(stage, result));
                }
            } else if (!pending.isEmpty()) {
                Pending futures = pending.remove(random.nextInt(pending.size()));
                int how = random.nextInt(4);
                switch (how) {
                    case 0 -> futures.stage().complete("later");
                    case 1 -> futures.stage().completeExceptionally(new IOException("later"));
                    case 2 -> futures.result().cancel(true);
                    default -> futures.stage().cancel(true); // the dependency's own future is cancelled
                }
                trace.step(List.of("complete", "fail", "cancel by the caller", "cancel the stage").get(how)
                        + " a pending call");
                trace.check(futures.stage().isDone() && futures.result().isDone(), "stage and result are both done");
                inside -= enabled ? 1 : 0;
            } else {
                trace.step("nothing");
            }
            trace.equal("concurrentCalls", enabled ? inside : 0, bulkhead.concurrentCalls());
            trace.equal("waitingCalls", 0, bulkhead.waitingCalls());
            trace.equal("snapshot", new BulkheadSnapshot("model", enabled ? inside : 0, 0, max), bulkhead.snapshot());
        }
        // Everything leaves: every slot comes back.
        for (Pending futures : pending) {
            futures.stage().complete("end");
        }
        for (int i = 0; i < syncInside; i++) {
            bulkhead.exit();
        }
        trace.step("everything leaves");
        trace.equal("concurrentCalls at the end", 0, bulkhead.concurrentCalls());
    }

    /** A call whose stage is still running: the dependency's stage and the future the bulkhead returned. */
    private record Pending(CompletableFuture<String> stage, CompletableFuture<String> result) {
    }

    private static boolean rejection(CompletableFuture<?> result) {
        try {
            result.join();
            return false;
        } catch (java.util.concurrent.CompletionException e) {
            return e.getCause() instanceof dev.resilience4cata.BulkheadFullException;
        } catch (java.util.concurrent.CancellationException e) {
            return false;
        }
    }
}
