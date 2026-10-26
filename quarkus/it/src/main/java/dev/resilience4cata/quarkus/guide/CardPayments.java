package dev.resilience4cata.quarkus.guide;

import jakarta.enterprise.context.ApplicationScoped;

// tag::quarkus-interface-bean[]
@ApplicationScoped
public class CardPayments implements PaymentsGateway {

    @Override
    public String charge(String order, long cents) {
        return "receipt-" + order; // a call to the payment provider in a real application
    }
}
// end::quarkus-interface-bean[]
