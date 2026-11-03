package dev.resilience4cata.spring.unprocessed;

/** A plain implementation of {@link StockApi}. */
public class StockApiImpl implements StockApi {

    @Override
    public int stock(String sku) {
        return 7;
    }
}
