package dev.resilience4cata.micronaut.load;

import dev.resilience4cata.BulkheadFullException;
import dev.resilience4cata.CallNotPermittedException;
import dev.resilience4cata.CallTimeoutException;
import dev.resilience4cata.RateLimitExceededException;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Error;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import java.util.concurrent.CompletableFuture;

/** One endpoint per protected method, on the blocking executor; each rejection or failure maps to its own status. */
@Controller("/load")
@Requires(env = "load")
@ExecuteOn(TaskExecutors.BLOCKING)
public class LoadController {

    private final InventoryService inventory;
    private final ShippingClient shipping;

    public LoadController(InventoryService inventory, ShippingClient shipping) {
        this.inventory = inventory;
        this.shipping = shipping;
    }

    @Get("/reserve/{sku}")
    int reserve(@PathVariable String sku) {
        return inventory.reserve(sku);
    }

    @Get("/queued/{sku}")
    int queued(@PathVariable String sku) {
        return inventory.queued(sku);
    }

    @Get("/later/{sku}")
    CompletableFuture<Integer> later(@PathVariable String sku) {
        return inventory.later(sku);
    }

    @Get("/stock/{sku}")
    int stock(@PathVariable String sku) {
        return inventory.stock(sku);
    }

    @Get("/quote/{sku}")
    int quote(@PathVariable String sku) {
        return inventory.quote(sku);
    }

    @Get("/slow/{sku}")
    int slow(@PathVariable String sku) {
        return inventory.slow(sku);
    }

    @Get("/ship/{id}")
    int ship(@PathVariable String id) {
        return shipping.ship(id);
    }

    @Error(exception = BulkheadFullException.class)
    HttpResponse<String> full() {
        return HttpResponse.status(HttpStatus.SERVICE_UNAVAILABLE).body("full");
    }

    @Error(exception = CallNotPermittedException.class)
    HttpResponse<String> open() {
        return HttpResponse.status(HttpStatus.SERVICE_UNAVAILABLE).body("open");
    }

    @Error(exception = RateLimitExceededException.class)
    HttpResponse<String> limited() {
        return HttpResponse.status(HttpStatus.TOO_MANY_REQUESTS).body("limited");
    }

    @Error(exception = CallTimeoutException.class)
    HttpResponse<String> timedOut() {
        return HttpResponse.status(HttpStatus.GATEWAY_TIMEOUT).body("timed out");
    }

    @Error(exception = IllegalStateException.class)
    HttpResponse<String> failed() {
        return HttpResponse.status(HttpStatus.BAD_GATEWAY).body("failed");
    }
}
