package dev.resilience4cata.quarkus.guide;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

// tag::quarkus-inject-interface[]
@ApplicationScoped
public class Checkout {

    @Inject
    PaymentsGateway payments; // the interface: the wrapper PaymentsGateway$Resilient around CardPayments

    public String pay(String order) {
        return payments.charge(order, 1999);
    }
}
// end::quarkus-inject-interface[]
