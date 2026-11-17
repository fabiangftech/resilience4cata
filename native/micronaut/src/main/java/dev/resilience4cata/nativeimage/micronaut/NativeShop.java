package dev.resilience4cata.nativeimage.micronaut;

import io.micronaut.runtime.Micronaut;

/** A small shop whose beans are protected by resilience4cata, built as a native image. */
public final class NativeShop {

    private NativeShop() {
    }

    public static void main(String[] args) {
        Micronaut.run(NativeShop.class, args);
    }
}
