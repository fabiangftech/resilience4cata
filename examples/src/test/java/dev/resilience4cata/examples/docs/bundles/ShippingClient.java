package dev.resilience4cata.examples.docs.bundles;

import dev.resilience4cata.annotation.Retry;
import java.io.IOException;

/** A bundle at type level, with one attribute overridden on a method. */
// tag::bundles-usage[]
@RemoteCall
public interface ShippingClient {

    String track(String parcel) throws IOException;

    @Retry(maxAttempts = 1)   // overrides one attribute of the bundle: never retried
    String cancel(String parcel) throws IOException;
}
// end::bundles-usage[]
