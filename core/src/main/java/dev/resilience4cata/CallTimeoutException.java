package dev.resilience4cata;

import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * Thrown when a call takes longer than its {@code @Timeout}. The call was interrupted when its time
 * ran out; if it then threw (for example an {@code InterruptedException}), that exception is the cause.
 * The interrupt is cleared before this exception is thrown, so it does not leak into later work.
 */
public final class CallTimeoutException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String instanceId;
    private final Duration timeout;

    /**
     * Creates the exception.
     *
     * @param instanceId id of the timeout policy
     * @param timeout the timeout that was exceeded
     * @param cause what the call threw after it was interrupted, or {@code null} if it returned
     */
    public CallTimeoutException(String instanceId, Duration timeout, @Nullable Throwable cause) {
        super("Call to '" + instanceId + "' timed out after " + timeout, cause, false, false);
        this.instanceId = instanceId;
        this.timeout = timeout;
    }

    /** Returns the id of the timeout policy. */
    public String instanceId() {
        return instanceId;
    }

    /** Returns the timeout that was exceeded. */
    public Duration timeout() {
        return timeout;
    }
}
