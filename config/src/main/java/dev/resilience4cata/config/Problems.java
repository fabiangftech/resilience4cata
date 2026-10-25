package dev.resilience4cata.config;

import java.util.ArrayList;
import java.util.List;

/** Collects every problem found, so that one {@link ConfigException} reports them all. */
final class Problems {

    private final List<ProblemException> collected = new ArrayList<>();

    void add(ProblemException problem) {
        collected.add(problem);
    }

    boolean isEmpty() {
        return collected.isEmpty();
    }

    void throwIfAny() {
        if (!collected.isEmpty()) {
            throw new ConfigException(collected.stream().map(ProblemException::render).toList());
        }
    }
}
