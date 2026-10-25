package dev.resilience4cata.examples.docs.bundles;

import dev.resilience4cata.annotation.Retry;

/** The wrapped interface: its type-level annotation beats the declaring superinterface's. */
@Retry(maxAttempts = 3)
public interface ChildApi extends BaseApi {
}
