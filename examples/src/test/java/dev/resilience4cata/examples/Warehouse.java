package dev.resilience4cata.examples;

/** The dependency of {@link StockService} in {@code docs/en/getting-started.md}. */
public interface Warehouse {
    int count(String sku);
}
