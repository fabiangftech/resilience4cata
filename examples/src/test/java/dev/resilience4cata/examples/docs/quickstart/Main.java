package dev.resilience4cata.examples.docs.quickstart;

// The program of docs/{en,es}/start/quickstart.md; QuickstartTest runs it and checks its output.
// tag::quickstart-main[]
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import java.io.IOException;

public class Main {

    public static void main(String[] args) throws IOException {
        ResilienceRegistry registry = ResilienceRegistry.builder().build();

        PriceClient prices = Resilient.of(PriceClient.class, new FlakyPriceClient(), registry);
        System.out.println(prices.price("sku-1"));   // 9.99: the first attempt failed, the retry succeeded

        PriceClient down = Resilient.of(PriceClient.class, sku -> {
            throw new IOException("service down");
        }, registry);
        System.out.println(down.price("sku-1"));     // 0: all three attempts failed, the fallback answered

        System.out.println(registry.circuitBreaker("prices").state());   // CLOSED: too few calls to judge yet
    }
}
// end::quickstart-main[]
