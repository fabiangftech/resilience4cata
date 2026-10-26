package dev.resilience4cata.micronaut.guide;

import dev.resilience4cata.ResilienceRegistry;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;

@Requires(env = "guide")
// tag::micronaut-factory[]
@Factory
public class InvoiceFactory {

    @Singleton
    InvoiceClient invoiceClient(ResilienceRegistry registry, @Value("${invoices.url}") String url) {
        return new InvoiceClient$Resilient(registry, url); // typed InvoiceClient, the generated subclass inside
    }
}
// end::micronaut-factory[]
