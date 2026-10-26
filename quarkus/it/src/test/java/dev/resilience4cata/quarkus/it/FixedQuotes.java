package dev.resilience4cata.quarkus.it;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class FixedQuotes implements Quotes {

    @Override
    public int quote(int sku) {
        return sku + 1;
    }
}
