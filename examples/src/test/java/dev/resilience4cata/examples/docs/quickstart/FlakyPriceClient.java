package dev.resilience4cata.examples.docs.quickstart;

// The implementation of docs/{en,es}/start/quickstart.md.
// tag::quickstart-implementation[]
import java.io.IOException;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;

/** Stands in for a real HTTP client: the first call fails, the next ones answer. */
public class FlakyPriceClient implements PriceClient {
    private final AtomicInteger calls = new AtomicInteger();

    @Override
    public BigDecimal price(String sku) throws IOException {
        if (calls.incrementAndGet() == 1) {
            throw new IOException("connection reset");
        }
        return new BigDecimal("9.99");
    }
}
// end::quickstart-implementation[]
