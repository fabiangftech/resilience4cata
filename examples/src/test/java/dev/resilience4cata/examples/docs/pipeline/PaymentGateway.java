package dev.resilience4cata.examples.docs.pipeline;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** A third-party payment API, written as a plain class to show that nothing has to be annotated. */
public class PaymentGateway {

    public Receipt charge(String order) throws IOException {
        return new Receipt(order, "charged");
    }

    public CompletionStage<Receipt> chargeAsync(String order) {
        return CompletableFuture.completedFuture(new Receipt(order, "charged"));
    }

    public void cancel(String order) throws IOException {
        if (order.isEmpty()) {
            throw new IOException("no order");
        }
    }

    public record Receipt(String order, String status) {
        static Receipt pending(int attempts) {
            return new Receipt("?", "pending after " + attempts + " attempts");
        }
    }
}
