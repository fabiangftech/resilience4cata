package dev.resilience4cata.config;

import java.util.List;

/**
 * Thrown when external configuration is invalid. Every problem found is reported at once, each with
 * a stable code ({@code R4C-CFG-nnn}), the offending key, where it came from (file and line,
 * environment variable or system property) and, when possible, the closest valid key.
 */
public final class ConfigException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    private final String[] problems;

    ConfigException(List<String> problems) {
        super(message(problems));
        this.problems = problems.toArray(String[]::new);
    }

    private static String message(List<String> problems) {
        StringBuilder message = new StringBuilder("Invalid resilience4cata configuration (")
                .append(problems.size()).append(problems.size() == 1 ? " problem):" : " problems):");
        for (String problem : problems) {
            message.append("\n  ").append(problem);
        }
        return message.toString();
    }

    /**
     * Returns each problem as one line, in the order found.
     *
     * @return the problems, never empty
     */
    public List<String> problems() {
        return List.of(problems);
    }
}
