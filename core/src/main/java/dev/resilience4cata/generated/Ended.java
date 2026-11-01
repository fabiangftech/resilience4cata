package dev.resilience4cata.generated;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * The stage of a stream that ended while it was subscribed ({@link Elements#ended()}): completed with
 * {@code null} and immutable, so that every policy layer can return this one instance instead of a completed
 * copy ({@link AsyncSupport#copy}). Every method that would complete, cancel or replace the result does nothing.
 */
final class Ended extends CompletableFuture<Void> {

    static final Ended INSTANCE = new Ended();

    private Ended() {
        super.complete(null);
    }

    @Override
    public boolean complete(Void value) {
        return false;
    }

    @Override
    public boolean completeExceptionally(Throwable failure) {
        return false;
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        return false;
    }

    @Override
    public void obtrudeValue(Void value) {
        // immutable: shared by every call
    }

    @Override
    public void obtrudeException(Throwable failure) {
        // immutable: shared by every call
    }

    @Override
    public CompletableFuture<Void> completeAsync(Supplier<? extends Void> supplier, Executor executor) {
        return this;
    }

    @Override
    public CompletableFuture<Void> completeAsync(Supplier<? extends Void> supplier) {
        return this;
    }

    @Override
    public CompletableFuture<Void> orTimeout(long timeout, TimeUnit unit) {
        return this;
    }

    @Override
    public CompletableFuture<Void> completeOnTimeout(Void value, long timeout, TimeUnit unit) {
        return this;
    }
}
