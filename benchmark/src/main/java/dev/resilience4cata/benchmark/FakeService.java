package dev.resilience4cata.benchmark;

/** Trivial work, so that the benchmark measures the library and not the call. Not thread-safe. */
final class FakeService implements Service {

    /** Stackless and preallocated, so both libraries pay the same (zero) cost to create it. */
    static final RuntimeException FAILURE = new RuntimeException("failure", null, false, false) {
        private static final long serialVersionUID = 1L;
    };

    private final String value = "ok";
    private boolean failNext = true;

    @Override
    public String call() {
        return value;
    }

    /** Fails every other attempt, so each call succeeds on the second attempt. */
    @Override
    public String failOnce() {
        boolean fail = failNext;
        failNext = !fail;
        if (fail) {
            throw FAILURE;
        }
        return value;
    }
}
