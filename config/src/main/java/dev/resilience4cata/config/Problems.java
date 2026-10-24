package dev.resilience4cata.config;

import java.util.ArrayList;
import java.util.List;

/** Collects every problem found, so that one {@link ConfigException} reports them all. */
final class Problems {

    private final List<ProblemException> problems = new ArrayList<>();

    void add(ProblemException problem) {
        problems.add(problem);
    }

    boolean isEmpty() {
        return problems.isEmpty();
    }

    void throwIfAny() {
        if (!problems.isEmpty()) {
            throw new ConfigException(problems.stream().map(ProblemException::render).toList());
        }
    }
}
