package dev.resilience4cata.micronaut.guide;

import dev.resilience4cata.annotation.Retry;

// tag::micronaut-factory-class[]
public class InvoiceClient { // no scope annotation: Micronaut does not create it itself

    private final String baseUrl;

    public InvoiceClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    @Retry(maxAttempts = 2)
    public String fetch(String number) {
        return baseUrl + "/invoices/" + number; // an HTTP call in a real application
    }
}
// end::micronaut-factory-class[]
