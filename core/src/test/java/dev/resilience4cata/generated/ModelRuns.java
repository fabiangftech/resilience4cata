package dev.resilience4cata.generated;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongConsumer;

/**
 * Runs the seeded model tests ({@code *ModelTest}): each seed drives a policy and a small reference model
 * through the same random operations and compares them after every step.
 *
 * <p>To replay one seed, pass it as {@code -Ptest.seed=<n>} to Gradle (forwarded to the test JVM as the
 * system property {@code test.seed}; not {@code r4c.*}, which the config module reads).
 */
final class ModelRuns {

    private ModelRuns() {
    }

    /** Runs {@code body} for seeds {@code 0 .. count - 1}, or only for {@code test.seed} when it is set. */
    static void forEachSeed(int count, LongConsumer body) {
        String only = System.getProperty("test.seed");
        if (only != null && !only.isBlank()) {
            body.accept(Long.parseLong(only.trim()));
            return;
        }
        for (long seed = 0; seed < count; seed++) {
            body.accept(seed);
        }
    }

    /** The operations of one seed, printed when the policy and the model disagree. */
    static final class Trace {
        private static final int SHOWN = 120;

        private final long seed;
        private final String setup;
        private final List<String> steps = new ArrayList<>();

        Trace(long seed, String setup) {
            this.seed = seed;
            this.setup = setup;
        }

        void step(String step) {
            steps.add(step);
        }

        /** Throws an {@link AssertionError} unless {@code expected} equals {@code actual}. */
        void equal(String what, Object expected, Object actual) {
            if (!expected.equals(actual)) {
                fail(what + ": model " + expected + ", policy " + actual);
            }
        }

        void check(boolean condition, String what) {
            if (!condition) {
                fail(what);
            }
        }

        AssertionError fail(String what) {
            StringBuilder message = new StringBuilder()
                    .append("seed ").append(seed).append(" (replay with -Ptest.seed=").append(seed).append("): ")
                    .append(what).append("\nsetup: ").append(setup).append("\nsteps (").append(steps.size())
                    .append("):\n");
            int from = Math.max(0, steps.size() - SHOWN);
            if (from > 0) {
                message.append("  ... ").append(from).append(" earlier steps\n");
            }
            for (int i = from; i < steps.size(); i++) {
                message.append("  ").append(i).append(": ").append(steps.get(i)).append('\n');
            }
            throw new AssertionError(message.toString());
        }
    }

    /** A clock the test moves by hand. */
    static final class Clock {
        long now;

        Clock(long start) {
            this.now = start;
        }

        long read() {
            return now;
        }
    }
}
