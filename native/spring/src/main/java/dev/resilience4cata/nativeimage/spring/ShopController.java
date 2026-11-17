package dev.resilience4cata.nativeimage.spring;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** The shop's HTTP API, and a way for the tests to make the warehouse fail. */
@RestController
public class ShopController {

    private final Inventory inventory;
    private final Prices prices;
    private final Warehouse warehouse;

    public ShopController(Inventory inventory, Prices prices, Warehouse warehouse) {
        this.inventory = inventory;
        this.prices = prices;
        this.warehouse = warehouse;
    }

    @GetMapping("/stock/{sku}")
    public int stock(@PathVariable String sku) {
        return inventory.stock(sku);
    }

    @GetMapping("/price/{sku}")
    public String price(@PathVariable String sku) {
        return prices.price(sku);
    }

    @PostMapping("/warehouse/failures/{count}")
    public void fail(@PathVariable int count) {
        warehouse.failures.set(count);
    }

    @GetMapping("/warehouse/calls")
    public int calls() {
        return warehouse.calls.getAndSet(0);
    }

    @GetMapping("/beans")
    public String beans() {
        return inventory.getClass().getName() + " " + prices.getClass().getName();
    }
}
