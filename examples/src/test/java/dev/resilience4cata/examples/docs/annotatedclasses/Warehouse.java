package dev.resilience4cata.examples.docs.annotatedclasses;

/** The dependency of {@link StockService}. */
public interface Warehouse {
    int count(String sku);
}
