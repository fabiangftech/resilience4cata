package dev.resilience4cata.spring.autoconfigure;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/** Shows an {@code R4C-SPR} error: a bean that cannot be protected as declared. */
final class ResilienceFailureAnalyzer extends AbstractFailureAnalyzer<ResilienceBeanException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, ResilienceBeanException cause) {
        return new FailureAnalysis(cause.description(), cause.action(), cause);
    }
}
