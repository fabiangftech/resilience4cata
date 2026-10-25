package dev.resilience4cata.examples.docs.bundles;

import java.io.IOException;

/** Type level against method level: the method's bundle beats the type's direct annotation. */
public interface CatalogClient {

    @Idempotent
    String product(String id) throws IOException;
}
