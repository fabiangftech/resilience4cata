package dev.resilience4cata.spring.tx;

import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.Hedge;
import dev.resilience4cata.annotation.Retry;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * R4C-SPR-004 for {@code Mono} and {@code Flux} methods: a reactive {@code @Transactional} proxy around the
 * generated subclass would run every attempt in one transaction too. The retry and hedge layers are inside the
 * {@code Monos.defer} or {@code Fluxes.defer} lambda, not in the override's own body.
 */
@Transactional
public class ReactiveLedger {

    @Retry
    public Mono<String> post(String entry) {
        return Mono.just("posted " + entry);
    }

    @Hedge(delay = "50ms")
    public Mono<String> read(String key) {
        return Mono.just(key);
    }

    @CircuitBreaker
    public Mono<String> guarded(String key) {
        return Mono.just(key);
    }

    @Retry
    public Flux<String> entries(String account) {
        return Flux.just(account + " 1", account + " 2");
    }

    @CircuitBreaker
    public Flux<String> guardedEntries(String account) {
        return Flux.just(account);
    }
}
