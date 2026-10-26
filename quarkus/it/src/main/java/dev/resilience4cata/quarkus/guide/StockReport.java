package dev.resilience4cata.quarkus.guide;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

// tag::quarkus-inject-rest-client[]
@ApplicationScoped
public class StockReport {

    @Inject
    StockClient stock; // no @RestClient: the protected client

    public String line(String sku) {
        return sku + ": " + stock.available(sku);
    }
}
// end::quarkus-inject-rest-client[]
