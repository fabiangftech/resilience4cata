package dev.resilience4cata.spring.autoconfigure;

import dev.resilience4cata.config.ConfigException;
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/** Shows every problem of an invalid {@code resilience4cata.*} configuration, with its key and where it was set. */
final class ConfigFailureAnalyzer extends AbstractFailureAnalyzer<ConfigException> {

    private static final String UNKNOWN_INSTANCE = "[R4C-CFG-013]";

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, ConfigException cause) {
        StringBuilder description = new StringBuilder("Invalid resilience4cata configuration (")
                .append(cause.problems().size()).append(cause.problems().size() == 1 ? " problem):\n" : " problems):\n");
        cause.problems().forEach(problem -> description.append("\n    ").append(problem));
        String action = "Fix the resilience4cata.* keys listed above, in the application properties or YAML,"
                + " environment variables or command line where they are set.";
        if (cause.problems().stream().anyMatch(problem -> problem.startsWith(UNKNOWN_INSTANCE))) {
            action += " An instance id that is not listed yet may belong to a bean created after startup (a @Lazy or"
                    + " prototype bean, or a Pipeline built later): if so, set"
                    + " resilience4cata.spring.verify-instances=warn, which logs these keys instead of failing.";
        }
        return new FailureAnalysis(description.toString(), action
                + " Each R4C-CFG code is explained at https://resilience4cata.dev/en/errors", cause);
    }
}
