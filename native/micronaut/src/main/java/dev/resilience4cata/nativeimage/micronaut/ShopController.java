package dev.resilience4cata.nativeimage.micronaut;

import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;

/** The shop's HTTP API, and a way for the tests to make the warehouse fail; blocking calls off the event loop. */
@Controller
@ExecuteOn(TaskExecutors.BLOCKING)
public class ShopController {

    private final Inventory inventory;
    private final Prices prices;
    private final Warehouse warehouse;

    public ShopController(Inventory inventory, Prices prices, Warehouse warehouse) {
        this.inventory = inventory;
        this.prices = prices;
        this.warehouse = warehouse;
    }

    @Get("/stock/{sku}")
    public int stock(String sku) {
        return inventory.stock(sku);
    }

    @Get("/price/{sku}")
    public String price(String sku) {
        return prices.price(sku);
    }

    @Post("/warehouse/failures/{count}")
    public void fail(int count) {
        warehouse.failures.set(count);
    }

    @Get("/warehouse/calls")
    public int calls() {
        return warehouse.calls.getAndSet(0);
    }

    @Get("/shop/beans")
    public String beans() {
        return inventory.getClass().getName() + " " + prices.getClass().getName();
    }
}
