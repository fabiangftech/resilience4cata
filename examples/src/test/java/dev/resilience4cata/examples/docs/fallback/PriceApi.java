package dev.resilience4cata.examples.docs.fallback;

// tag::fallback-minimal[]
import dev.resilience4cata.annotation.Fallback;

public interface PriceApi {

    @Fallback(method = "unknownPrice")
    long price(String sku);

    default long unknownPrice(String sku, Exception failure) {
        return -1;
    }
}
// end::fallback-minimal[]
