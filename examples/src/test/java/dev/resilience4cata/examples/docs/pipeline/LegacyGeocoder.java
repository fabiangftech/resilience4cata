package dev.resilience4cata.examples.docs.pipeline;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/** A third-party class that cannot be annotated: fails once, then answers. */
public final class LegacyGeocoder {
    private final AtomicInteger calls = new AtomicInteger();

    public String city(String zip) throws IOException {
        if (calls.incrementAndGet() == 1) {
            throw new IOException("timeout");
        }
        return "Berlin";
    }
}
