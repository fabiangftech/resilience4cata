package dev.resilience4cata.generated;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.function.LongSupplier;

/**
 * When one attempt of a streaming call emitted its first element: what the circuit breaker's slow-call
 * detection and the adaptive limit's latency samples measure for a {@code Flux}, since a stream's length
 * says nothing about the dependency. One clock read, at the first element only.
 */
final class FirstElement implements ElementListener {

    private static final VarHandle AT;

    static {
        try {
            AT = MethodHandles.lookup().findVarHandle(FirstElement.class, "at", long.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final LongSupplier ticker;

    // java:S1845: the field and its VarHandle share a name, the usual VarHandle naming convention.
    @SuppressWarnings({"unused", "java:S1845"}) // accessed through AT
    private volatile long at;

    private volatile boolean seen;

    FirstElement(LongSupplier ticker) {
        this.ticker = ticker;
    }

    @Override
    public void element() {
        AT.setOpaque(this, ticker.getAsLong());
        seen = true; // volatile: a reader that sees it sees the time written before
    }

    /** When the first element arrived, or now if none did (the stream ended, or failed, before one). */
    long time() {
        return seen ? (long) AT.getOpaque(this) : ticker.getAsLong();
    }

    /** When the first element arrived, or {@code now} if none did. */
    long time(long now) {
        return seen ? (long) AT.getOpaque(this) : now;
    }
}
