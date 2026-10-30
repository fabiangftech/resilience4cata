package dev.resilience4cata.spring.load;

import dev.resilience4cata.BulkheadFullException;
import dev.resilience4cata.CallNotPermittedException;
import dev.resilience4cata.CallTimeoutException;
import dev.resilience4cata.RateLimitExceededException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** One endpoint per protected method; each rejection or failure maps to its own status. */
@RestController
public class LoadController {

    private final InventoryService inventory;
    private final ShippingClient shipping;

    public LoadController(InventoryService inventory, ShippingClient shipping) {
        this.inventory = inventory;
        this.shipping = shipping;
    }

    @GetMapping("/reserve/{sku}")
    int reserve(@PathVariable String sku) {
        return inventory.reserve(sku);
    }

    @GetMapping("/stock/{sku}")
    int stock(@PathVariable String sku) {
        return inventory.stock(sku);
    }

    @GetMapping("/quote/{sku}")
    int quote(@PathVariable String sku) {
        return inventory.quote(sku);
    }

    @GetMapping("/slow/{sku}")
    int slow(@PathVariable String sku) {
        return inventory.slow(sku);
    }

    @GetMapping("/queued/{sku}")
    int queued(@PathVariable String sku) {
        return inventory.queued(sku);
    }

    @GetMapping("/later/{sku}")
    java.util.concurrent.CompletableFuture<Integer> later(@PathVariable String sku) {
        return inventory.later(sku);
    }

    @GetMapping("/ship/{id}")
    int ship(@PathVariable String id) {
        return shipping.ship(id);
    }

    @ExceptionHandler(BulkheadFullException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    String full(BulkheadFullException e) {
        return "full";
    }

    @ExceptionHandler(CallNotPermittedException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    String open(CallNotPermittedException e) {
        return "open";
    }

    @ExceptionHandler(RateLimitExceededException.class)
    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    String limited(RateLimitExceededException e) {
        return "limited";
    }

    @ExceptionHandler(CallTimeoutException.class)
    @ResponseStatus(HttpStatus.GATEWAY_TIMEOUT)
    String timedOut(CallTimeoutException e) {
        return "timed out";
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    String failed(IllegalStateException e) {
        return "failed";
    }
}
