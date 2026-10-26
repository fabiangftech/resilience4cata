package dev.resilience4cata.micronaut.fixtures;

import dev.resilience4cata.micronaut.ResilientBean;
import jakarta.inject.Singleton;

/** Fails as long as the {@link Outage} says so. */
@Singleton
@ResilientBean(Payments.class)
public class HttpPayments implements Payments {

    private final Outage outage;

    public HttpPayments(Outage outage) {
        this.outage = outage;
    }

    @Override
    public int charge(int cents) {
        outage.call();
        return cents;
    }
}
