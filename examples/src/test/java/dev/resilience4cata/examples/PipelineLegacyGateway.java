package dev.resilience4cata.examples;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntPredicate;

/**
 * Stands for a third-party class: final, not an interface, so it cannot be annotated or wrapped with
 * {@code Resilient.of}. Call {@code n} (1-based) fails with an {@link IOException} when the script says so.
 * Thread-safe; {@code synchronized} is fine here, in a test double.
 */
public final class PipelineLegacyGateway {

    private final IntPredicate fails;
    private final IntPredicate busy;
    private int calls;

    public PipelineLegacyGateway(IntPredicate fails) {
        this(fails, call -> false);
    }

    /** Also answers {@code "busy"} instead of a receipt when {@code busy} says so. */
    public PipelineLegacyGateway(IntPredicate fails, IntPredicate busy) {
        this.fails = fails;
        this.busy = busy;
    }

    public synchronized String charge(String order) throws IOException {
        int call = ++calls;
        if (fails.test(call)) {
            throw new IOException("gateway down (call " + call + ")");
        }
        if (busy.test(call)) {
            return "busy (call " + call + ")";
        }
        return "receipt " + order + " (call " + call + ")";
    }

    public CompletableFuture<String> chargeAsync(String order) {
        CompletableFuture<String> result = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try {
                result.complete(charge(order));
            } catch (IOException e) {
                result.completeExceptionally(e);
            }
        });
        return result;
    }

    public synchronized int calls() {
        return calls;
    }
}
