package dev.resilience4cata.examples.docs.hedge;

// tag::hedge-minimal[]
import dev.resilience4cata.annotation.Hedge;
import java.util.concurrent.CompletableFuture;

public interface QuoteApi {

    @Hedge(delay = "50ms")
    CompletableFuture<String> quote(String sku);
}
// end::hedge-minimal[]
