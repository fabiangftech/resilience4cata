package dev.resilience4cata.spring.tx;

import dev.resilience4cata.annotation.Hedge;
import java.util.concurrent.CompletableFuture;
import org.springframework.transaction.annotation.Transactional;

/** R4C-SPR-004: {@code @Hedge} on a method, {@code @Transactional} on the class. */
@Transactional
public class HedgedLedger {

    @Hedge(delay = "50ms")
    public CompletableFuture<String> read(String key) {
        return CompletableFuture.completedFuture(key);
    }
}
