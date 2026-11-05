package dev.resilience4cata.spring.unprocessed;

/** A class with an order-only bundle: no policy, so no generated class is expected. */
@TimeoutFirst
public class OrderedService {

    public String price(String sku) {
        return "9.99";
    }
}
