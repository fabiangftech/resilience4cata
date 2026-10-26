package dev.resilience4cata.micronaut.fixtures;

import dev.resilience4cata.micronaut.ResilientBean;
import jakarta.inject.Singleton;

@Singleton
@ResilientBean(Ledger.class)
public class RemoteLedger implements Ledger {

    private final Outage outage;

    public RemoteLedger(Outage outage) {
        this.outage = outage;
    }

    @Audited
    @Override
    public int post(int cents) {
        outage.call();
        return cents;
    }
}
