package dev.resilience4cata;

import java.util.concurrent.CancellationException;

/**
 * Completes the stage of a hedge attempt that lost: another attempt succeeded first, or the call ended
 * (an {@code abortOn} failure, or a cancel). It is a {@link CancellationException}, so the attempt's stage
 * reports {@code isCancelled()}, and the policies inside the attempt treat it as a cancel: a circuit
 * breaker records nothing, a bulkhead frees the slot. Each losing attempt gets its own instance, without a
 * stack trace and with its message built only when read. It never reaches the caller of the hedged method.
 */
public final class HedgeCancelledException extends CancellationException {

    private static final long serialVersionUID = 1L;

    /** The id of the hedge. */
    private final String id;

    /**
     * Creates the exception for one hedge.
     *
     * @param id id of the hedge
     */
    public HedgeCancelledException(String id) {
        super(null);
        this.id = id;
    }

    /** Returns the id of the hedge that cancelled the attempt. */
    public String id() {
        return id;
    }

    @Override
    public String getMessage() {
        return "Hedge '" + id + "' cancelled this attempt: another attempt succeeded first, or the call ended";
    }

    /** No stack trace: it marks a cancel, and is created only for attempts that lost. */
    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
