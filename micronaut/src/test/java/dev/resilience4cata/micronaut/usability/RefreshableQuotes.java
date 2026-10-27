package dev.resilience4cata.micronaut.usability;

import dev.resilience4cata.annotation.Retry;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.runtime.context.scope.Refreshable;

/** An annotated class in a scope that Micronaut proxies: the proxy targets the generated subclass. */
@Refreshable
@Requires(property = "test.usability", value = "scopes")
public class RefreshableQuotes {

    @Value("${quotes.prefix:q}")
    String prefix;

    private final Failures failures;

    public RefreshableQuotes(Failures failures) {
        this.failures = failures;
    }

    @Retry(maxAttempts = 3)
    public String quote(String sku) {
        failures.call();
        return prefix + "-" + sku;
    }
}
