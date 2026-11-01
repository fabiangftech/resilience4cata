package dev.resilience4cata.benchmark.quarkus;

/** One per benchmark thread: every other attempt fails, so a retried call fails once and then succeeds. */
public final class Flip {

    /** Stackless and shared, so neither library pays for a stack trace. */
    static final RuntimeException FAILURE = new RuntimeException("planned failure", null, false, false) { };

    private boolean failNext = true;

    /** Throws on every other call. */
    void attempt() {
        boolean fail = failNext;
        failNext = !fail;
        if (fail) {
            throw FAILURE;
        }
    }
}
