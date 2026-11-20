package dev.resilience4cata.nativeimage.plainjava;

/** An application exception that {@code resilience4cata.properties} names by its class name. */
public class OutOfStockException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public OutOfStockException(String message) {
        super(message);
    }
}
