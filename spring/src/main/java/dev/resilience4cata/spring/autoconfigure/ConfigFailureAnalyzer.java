package dev.resilience4cata.spring.autoconfigure;

import dev.resilience4cata.config.ConfigException;
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/** Shows every problem of an invalid {@code resilience4cata.*} configuration, with its key and where it was set. */
final class ConfigFailureAnalyzer extends AbstractFailureAnalyzer<ConfigException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, ConfigException cause) {
        StringBuilder description = new StringBuilder("Invalid resilience4cata configuration (")
                .append(cause.problems().size()).append(cause.problems().size() == 1 ? " problem):\n" : " problems):\n");
        cause.problems().forEach(problem -> description.append("\n    ").append(problem));
        return new FailureAnalysis(description.toString(), "Fix the resilience4cata.* keys listed above, in the"
                + " application properties or YAML, environment variables or command line where they are set."
                + " Each R4C-CFG code is explained at https://resilience4cata.dev/#/en/errors", cause);
    }
}
