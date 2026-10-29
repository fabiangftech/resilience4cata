package dev.resilience4cata.spring.reactive;

import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** Puts the request's {@code X-Request-Id} into the Reactor context, as tracing and security filters do. */
@Component
public class RequestIdFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String id = exchange.getRequest().getHeaders().getFirst("X-Request-Id");
        return id == null ? chain.filter(exchange) : chain.filter(exchange).contextWrite(c -> c.put("requestId", id));
    }
}
