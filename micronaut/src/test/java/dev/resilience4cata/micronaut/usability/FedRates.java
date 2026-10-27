package dev.resilience4cata.micronaut.usability;

import dev.resilience4cata.micronaut.ResilientBean;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

@Singleton
@Named("fed")
@ResilientBean(Rates.class)
@Requires(property = "test.usability", value = "qualifiers")
public class FedRates implements Rates {

    private final Failures failures;

    public FedRates(Failures failures) {
        this.failures = failures;
    }

    @Override
    public double rate(String currency) {
        failures.call();
        return 4;
    }
}
