package dev.resilience4cata.examples.docs.quickstart;

// Step 6 of docs/{en,es}/start/quickstart.md; QuickstartTest runs it and checks its output.
// tag::quickstart-breaker-opens[]
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

public class BreakerOpens {

    public static void main(String[] args) throws IOException {
        ResilienceRegistry registry = ResilienceRegistry.builder()
                .circuitBreakerInstance("prices", b -> b.windowSize(4).minimumCalls(4))   // judge after 4 calls
                .build();

        AtomicInteger calls = new AtomicInteger();
        PriceClient down = Resilient.of(PriceClient.class, sku -> {
            calls.incrementAndGet();
            throw new IOException("service down");
        }, registry);

        down.price("sku-1");   // three attempts, three failures
        down.price("sku-2");   // the fourth failure opens the breaker, which stops the retry
        System.out.println(registry.circuitBreaker("prices").state());   // OPEN

        System.out.println(down.price("sku-3"));   // 0: rejected at once, answered by the fallback
        System.out.println(calls.get());           // 4: the service was not called again
    }
}
// end::quickstart-breaker-opens[]
