package dev.resilience4cata.examples.docs.troubleshooting;

import java.util.List;

// java:S1172: fetchPrice stands in for a remote call; suppressed on the class, which is a documentation snippet
@SuppressWarnings("java:S1172")
// tag::troubleshooting-self-invocation[]
final class HttpCatalog implements CatalogApi {

    @Override
    public int price(String sku) {
        return fetchPrice(sku); // may fail
    }

    @Override
    public int total(List<String> skus) {
        // this.price(...) calls the implementation directly, not the generated wrapper: no retry here
        return skus.stream().mapToInt(this::price).sum();
    }

    private int failuresLeft = 1; // a flaky remote call: fails once

    private int fetchPrice(String sku) {
        if (failuresLeft-- > 0) {
            throw new IllegalStateException("flaky");
        }
        return 10;
    }
}
// end::troubleshooting-self-invocation[]
