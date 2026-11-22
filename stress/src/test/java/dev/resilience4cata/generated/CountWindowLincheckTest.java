package dev.resilience4cata.generated;

import java.util.Arrays;
import org.jetbrains.lincheck.datastructures.Operation;
import org.jetbrains.lincheck.datastructures.Validate;
import org.junit.jupiter.api.Test;

/**
 * The breaker's count window is not linearizable by design (its counters may lag for an instant under concurrent
 * records), so its operations return nothing here and Lincheck checks what it does promise instead: once the threads
 * are done, under every interleaving explored, the counters agree with the slots. With a window of 2 slots, records
 * of the same slot (cursor {@code i} and {@code i + 2}) race each other.
 */
public class CountWindowLincheckTest {

    private final CountWindow window = new CountWindow(2, true);

    @Operation
    public void success() {
        window.record(0, 0);
    }

    @Operation
    public void failure() {
        window.record(SlidingWindow.FAILURE, 0);
    }

    @Operation
    public void slowFailure() {
        window.record(SlidingWindow.FAILURE | SlidingWindow.SLOW, 0);
    }

    @Validate
    public void countersAgreeWithTheSlots() {
        int[] counters = {window.calls(), window.failures(), window.slowCalls()};
        int[] slots = window.recount();
        if (!Arrays.equals(counters, slots)) {
            throw new IllegalStateException("counters " + Arrays.toString(counters) + " but slots " + Arrays.toString(slots));
        }
    }

    @Test
    void theCountersNeverDrift() {
        Lincheck.modelChecking().check(getClass());
    }
}
