package dev.resilience4cata.examples.docs.bundles;

import dev.resilience4cata.annotation.Retry;
import java.io.IOException;

/** Type-level annotations apply to every method; a method-level bundle still wins over them. */
@Retry(maxAttempts = 2, config = "external")
interface LegacyCatalogClient {

    @Idempotent
    String product(String id) throws IOException;

    String category(String id) throws IOException;
}
