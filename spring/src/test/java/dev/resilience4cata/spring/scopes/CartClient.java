package dev.resilience4cata.spring.scopes;

import dev.resilience4cata.annotation.Retry;
import java.util.List;

/** An annotated interface whose implementation is session-scoped. */
public interface CartClient {

    @Retry(maxAttempts = 3, delay = "5ms")
    int add(String item);

    List<String> items();
}
