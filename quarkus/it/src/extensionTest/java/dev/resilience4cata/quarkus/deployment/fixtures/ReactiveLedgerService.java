package dev.resilience4cata.quarkus.deployment.fixtures;

import dev.resilience4cata.annotation.Hedge;
import dev.resilience4cata.annotation.Retry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * R4C-QK-002 for {@code Mono} and {@code Flux} methods: their retry and hedge layers are in the lambda that the
 * generated override returns, not in its own body.
 */
@ApplicationScoped
public class ReactiveLedgerService {

    @Retry(maxAttempts = 3)
    @Transactional
    public Mono<String> post(String entry) {
        return Mono.just(entry);
    }

    @Hedge(delay = "50ms")
    @Transactional
    public Mono<String> read(String key) {
        return Mono.just(key);
    }

    @Retry(maxAttempts = 3)
    @Transactional
    public Flux<String> entries(String account) {
        return Flux.just(account);
    }
}
