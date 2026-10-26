package dev.resilience4cata.micronaut.fixtures;

import dev.resilience4cata.micronaut.ResilientBean;
import jakarta.inject.Singleton;

@Singleton
@ResilientBean(Shipping.class)
public class LocalShipping implements Shipping {

    private final Outage outage;

    public LocalShipping(Outage outage) {
        this.outage = outage;
    }

    @Override
    public String track(String parcel) {
        return "in transit: " + parcel;
    }

    @Override
    public int quote(String parcel) {
        outage.call();
        return 7;
    }
}
