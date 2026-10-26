package dev.resilience4cata.quarkus.deployment;

import java.util.ArrayList;
import java.util.List;

/** The messages of a failure and its causes and suppressed exceptions, for assertions. */
final class Failures {

    private Failures() {
    }

    static String messages(Throwable failure) {
        List<String> messages = new ArrayList<>();
        collect(failure, messages, 0);
        return String.join("\n", messages);
    }

    private static void collect(Throwable t, List<String> messages, int depth) {
        if (t == null || depth > 20) {
            return;
        }
        messages.add(t.getClass().getName() + ": " + t.getMessage());
        for (Throwable suppressed : t.getSuppressed()) {
            collect(suppressed, messages, depth + 1);
        }
        collect(t.getCause(), messages, depth + 1);
    }
}
