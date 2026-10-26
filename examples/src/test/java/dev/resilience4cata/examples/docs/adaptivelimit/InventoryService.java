package dev.resilience4cata.examples.docs.adaptivelimit;

import dev.resilience4cata.annotation.AdaptiveLimit;

/** An annotated class: the generated subclass applies the limit. */
// tag::adaptive-limit-class[]
@AdaptiveLimit(maxLimit = 50)
public class InventoryService {

    public int stock(String sku) {
        return 7;
    }
}
// end::adaptive-limit-class[]
